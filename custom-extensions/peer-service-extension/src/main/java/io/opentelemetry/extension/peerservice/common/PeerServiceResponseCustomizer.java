/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.common;

import com.google.auto.service.AutoService;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseCustomizer;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseMutator;
import java.util.logging.Logger;

/**
 * Server 端 Response Header 回传实现。
 *
 * <p>通过 {@link HttpServerResponseCustomizer} SPI 机制，在每个 HTTP Response 中写入
 * {@code x-otel-service-name} Header，值为本服务的 {@code service.name}。
 *
 * <p>这样 Client 端可以通过 {@code capturedResponseHeaders} 配置自动捕获该 Header，
 * 再由 {@code PeerServiceSpanProcessor}（SDK 层）将其转换为 {@code peer.service} 属性。
 *
 * <p>覆盖范围：所有已集成 {@code HttpServerResponseCustomizerHolder} 的 Server Instrumentation
 * （Servlet 2.2/3.0/5.0、Tomcat、Jetty、Undertow、Netty、Spring WebFlux 等）自动生效，零侵入。
 *
 * <p>优雅降级：如果 service.name 未配置或获取失败，则不写入 Header，不影响正常功能。
 *
 * <p><b>ClassLoader 隔离处理：</b>本类可能被多个 ClassLoader 加载（AgentClassLoader 和
 * AppClassLoader），导致静态字段 {@code serviceName} 在不同 ClassLoader 中不共享。
 * 为解决此问题，{@link #setServiceName(String)} 同时将值写入 {@link System#setProperty}，
 * {@link #getServiceName()} 在静态字段为 null 时自动 fallback 到 {@link System#getProperty}。
 * {@code System} 类位于 bootstrap ClassLoader，所有 ClassLoader 共享同一个
 * {@code System.getProperties()}，从而实现跨 ClassLoader 的数据共享。
 */
@AutoService(HttpServerResponseCustomizer.class)
public class PeerServiceResponseCustomizer implements HttpServerResponseCustomizer {

  private static final Logger logger =
      Logger.getLogger(PeerServiceResponseCustomizer.class.getName());

  /** 统一的 service name header 名称，HTTP / gRPC / Dubbo 共用 */
  public static final String SERVICE_NAME_HEADER = "x-otel-service-name";

  /**
   * System Property key，用于跨 ClassLoader 共享 service.name。
   *
   * <p>当本类被多个 ClassLoader 加载时（如 AgentClassLoader 和 AppClassLoader），
   * 静态字段 {@code serviceName} 在不同 ClassLoader 中各有一份副本，无法共享。
   * 通过 System Property 作为 bootstrap ClassLoader 可见的全局存储，
   * 实现跨 ClassLoader 的数据桥梁。
   */
  static final String SERVICE_NAME_SYSTEM_PROPERTY = "otel.peer.service.local.name";

  /**
   * 缓存的 service.name 值。
   * 由 {@link PeerServiceAutoConfigurationCustomizerProvider} 在 SDK 初始化时通过
   * {@link #setServiceName(String)} 设置。
   *
   * <p>注意：由于 ClassLoader 隔离，此字段仅在设置它的 ClassLoader 中有效。
   * 其他 ClassLoader 中的副本需要通过 {@link #getServiceName()} 的 fallback 机制
   * 从 System Property 中读取。
   */
  private static volatile String serviceName;

  /**
   * 设置本服务的 service.name，由 {@link PeerServiceAutoConfigurationCustomizerProvider}
   * 在 SDK 初始化阶段调用。
   *
   * <p>同时将值写入 System Property（{@value #SERVICE_NAME_SYSTEM_PROPERTY}），
   * 确保被其他 ClassLoader 加载的本类副本也能通过 {@link #getServiceName()} 获取到值。
   *
   * @param name 本服务的 service.name
   */
  public static void setServiceName(String name) {
    serviceName = name;
    // 同时写入 System Property，作为跨 ClassLoader 的数据桥梁。
    // System 类位于 bootstrap ClassLoader，所有 ClassLoader 共享同一个 System.getProperties()。
    System.setProperty(SERVICE_NAME_SYSTEM_PROPERTY, name);
    logger.info("PeerServiceResponseCustomizer initialized with service.name: " + name);
  }

  /**
   * 获取当前缓存的 service.name。
   *
   * <p>优先返回本 ClassLoader 中的静态字段值；如果为 null（说明本类被另一个 ClassLoader 加载，
   * 而 {@link #setServiceName(String)} 是在其他 ClassLoader 中调用的），
   * 则 fallback 到 System Property 读取，并缓存到本地静态字段以避免重复读取。
   *
   * @return 当前缓存的 service.name，可能为 null
   */
  public static String getServiceName() {
    String name = serviceName;
    if (name != null) {
      return name;
    }
    // Fallback：从 System Property 读取（跨 ClassLoader 共享）
    name = System.getProperty(SERVICE_NAME_SYSTEM_PROPERTY);
    if (name != null && !name.isEmpty()) {
      // 缓存到本地静态字段，避免每次都读取 System Property
      serviceName = name;
    }
    return name;
  }

  @Override
  public <T> void customize(
      Context serverContext, T response, HttpServerResponseMutator<T> responseMutator) {
    String name = getServiceName();
    if (name != null && !name.isEmpty()) {
      responseMutator.appendHeader(response, SERVICE_NAME_HEADER, name);
    }
  }
}
