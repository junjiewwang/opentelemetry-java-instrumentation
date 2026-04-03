/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.grpc;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.extendsClass;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.grpc.ClientInterceptor;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * gRPC Client 端 bytecode weaving，在 {@code ManagedChannelBuilder.build()} 时自动注入
 * {@link PeerServiceGrpcClientInterceptor}。
 *
 * <p>模式与 OTel 原生的 {@code GrpcClientBuilderBuildInstrumentation} 一致：
 * <ul>
 *   <li>匹配 {@code ManagedChannelBuilder} 的子类且声明了 {@code interceptors} 字段</li>
 *   <li>在 {@code build()} 方法的 {@code @OnMethodEnter} 中，将自定义 interceptor
 *       添加到 {@code interceptors} 列表末尾</li>
 * </ul>
 *
 * <p><b>关键设计：</b>OTel 原生使用 {@code interceptors.add(0, ...)} 将
 * {@code TracingClientInterceptor} 插入到列表头部（索引 0），而我们使用
 * {@code interceptors.add(...)} 追加到末尾。这保证了 gRPC interceptor 链的执行顺序：
 * <ol>
 *   <li>OTel 的 {@code TracingClientInterceptor} 先执行，创建 context 和 span</li>
 *   <li>我们的 {@code PeerServiceGrpcClientInterceptor} 后执行，包装 responseListener</li>
 *   <li>在 {@code onHeaders} 回调中，OTel 先 {@code context.makeCurrent()}，
 *       然后调用我们的 listener，此时 {@code Span.current()} 是 gRPC Client Span</li>
 * </ol>
 *
 * @see PeerServiceGrpcClientInterceptor
 */
public class PeerServiceGrpcClientBuilderInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("io.grpc.ManagedChannelBuilder");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(named("io.grpc.ManagedChannelBuilder"))
        .and(declaresField(named("interceptors")));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod().and(named("build")),
        PeerServiceGrpcClientBuilderInstrumentation.class.getName() + "$AddInterceptorAdvice");
  }

  @SuppressWarnings("unused")
  public static class AddInterceptorAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addInterceptor(
        @Advice.FieldValue("interceptors") List<ClientInterceptor> interceptors) {
      // 追加到末尾（不是 add(0, ...)），确保在 OTel TracingClientInterceptor 之后执行。
      // 这样在 onHeaders 回调中，OTel 的 TracingClientCallListener 会先执行
      // context.makeCurrent()，然后调用我们的 listener，Span.current() 就是 Client Span。
      interceptors.add(new PeerServiceGrpcClientInterceptor());
    }
  }
}
