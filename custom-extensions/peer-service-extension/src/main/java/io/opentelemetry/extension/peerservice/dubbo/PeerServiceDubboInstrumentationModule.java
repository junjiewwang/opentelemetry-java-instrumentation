/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.HelperResourceBuilder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.internal.injection.ClassInjector;
import io.opentelemetry.javaagent.extension.instrumentation.internal.injection.InjectionMode;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Dubbo Peer Service InstrumentationModule，通过 Dubbo SPI 机制注入 Server 端和 Client 端 Filter。
 *
 * <p>参照 OTel 原生的 {@code DubboInstrumentationModule} 实现，使用
 * {@link ExperimentalInstrumentationModule} 的 {@code registerHelperResources} 和
 * {@code injectClasses} 机制，将自定义的 Dubbo Filter 注入到应用 classloader 中。
 *
 * <p>通过 {@link AutoService} SPI 机制自动注册到 agent 中。当目标应用的 classpath 中存在
 * Dubbo 相关类时，muzzle 检查通过后自动生效；如果没有 Dubbo 依赖，则自动跳过。
 *
 * <p>模块名为 {@code peer-service-dubbo}，可通过
 * {@code otel.instrumentation.peer-service-dubbo.enabled=false} 配置禁用。
 */
@AutoService(InstrumentationModule.class)
public class PeerServiceDubboInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public PeerServiceDubboInstrumentationModule() {
    super("peer-service-dubbo");
  }

  @Override
  public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
    // 注册 Dubbo SPI 文件，将自定义 Filter 注册到 Dubbo 的 Filter 链中
    helperResourceBuilder.register(
        "META-INF/services/org.apache.dubbo.rpc.Filter",
        "peer-service-dubbo/META-INF/org.apache.dubbo.rpc.Filter");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.apache.dubbo.rpc.Filter");
  }

  @Override
  public void injectClasses(ClassInjector injector) {
    // 将 Filter 类注入到应用 classloader 中，使 Dubbo SPI 能够加载
    injector
        .proxyBuilder(
            "io.opentelemetry.extension.peerservice.dubbo.PeerServiceDubboClientFilter")
        .inject(InjectionMode.CLASS_ONLY);
    injector
        .proxyBuilder(
            "io.opentelemetry.extension.peerservice.dubbo.PeerServiceDubboServerFilter")
        .inject(InjectionMode.CLASS_ONLY);
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    // 需要一个 TypeInstrumentation 来触发 resource injection
    return singletonList(new ResourceInjectingTypeInstrumentation());
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.startsWith("io.opentelemetry.extension.peerservice.");
  }

  /**
   * 空的 TypeInstrumentation，仅用于触发 resource injection。
   * 参照 OTel 原生 DubboInstrumentationModule 的实现模式。
   */
  public static class ResourceInjectingTypeInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("org.apache.dubbo.common.extension.ExtensionLoader");
    }

    @Override
    public void transform(TypeTransformer transformer) {
      // 不做任何 transform，仅用于触发 resource injection
    }
  }
}
