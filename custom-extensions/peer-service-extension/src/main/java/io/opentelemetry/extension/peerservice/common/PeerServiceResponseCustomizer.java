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
 */
@AutoService(HttpServerResponseCustomizer.class)
public class PeerServiceResponseCustomizer implements HttpServerResponseCustomizer {

  private static final Logger logger =
      Logger.getLogger(PeerServiceResponseCustomizer.class.getName());

  /** 统一的 service name header 名称，HTTP / gRPC / Dubbo 共用 */
  public static final String SERVICE_NAME_HEADER = "x-otel-service-name";

  /**
   * 缓存的 service.name 值。
   * 由 {@link PeerServiceAutoConfigurationCustomizerProvider} 在 SDK 初始化时通过
   * {@link #setServiceName(String)} 设置。
   */
  private static volatile String serviceName;

  /**
   * 设置本服务的 service.name，由 {@link PeerServiceAutoConfigurationCustomizerProvider}
   * 在 SDK 初始化阶段调用。
   *
   * @param name 本服务的 service.name
   */
  public static void setServiceName(String name) {
    serviceName = name;
    logger.info("PeerServiceResponseCustomizer initialized with service.name: " + name);
  }

  /**
   * 获取当前缓存的 service.name。
   *
   * @return 当前缓存的 service.name，可能为 null
   */
  public static String getServiceName() {
    return serviceName;
  }

  @Override
  public <T> void customize(
      Context serverContext, T response, HttpServerResponseMutator<T> responseMutator) {
    String name = serviceName;
    if (name != null && !name.isEmpty()) {
      responseMutator.appendHeader(response, SERVICE_NAME_HEADER, name);
    }
  }
}
