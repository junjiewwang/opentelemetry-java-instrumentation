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
 * <p>模式与 OTel 原生的 {@code GrpcClientBuilderBuildInstrumentation} 一致，
 * 但注入的是我们自定义的 peer service interceptor，不修改 OTel 源码。
 *
 * <p>将 interceptor 添加到 interceptors 列表的 <b>index 0</b> 位置，与 OTel 原生的注入方式一致。
 * 由于 {@link PeerServiceGrpcClientInterceptor} 在 {@code start()} 中捕获 Span（而非
 * {@code interceptCall()} 中），无论本 Advice 与 OTel Advice 的执行顺序如何，
 * interceptor 在列表中的位置不影响正确性。
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
      // 添加到 index 0，与 OTel 原生 TracingClientInterceptor 的注入方式一致。
      // interceptor 在列表中的位置不影响正确性，因为 Span 捕获在 start() 中完成，
      // 此时一定在 OTel TracingClientCall.start() 的 context.makeCurrent() scope 内。
      interceptors.add(0, new PeerServiceGrpcClientInterceptor());
    }
  }
}
