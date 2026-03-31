/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * SDK 自动配置定制器，负责两件事：
 *
 * <ol>
 *   <li><b>获取 service.name 并注入到 {@link PeerServiceResponseCustomizer}</b>：
 *       通过 {@code addResourceCustomizer} 在 Resource 构建完成后读取 {@code service.name}，
 *       缓存到 {@link PeerServiceResponseCustomizer} 中，使其能在 Response Header 中回传。
 *   <li><b>配置 Client 端自动捕获 {@code x-otel-service-name} Response Header</b>：
 *       通过 {@code addPropertiesCustomizer} 将 {@code x-otel-service-name} 追加到
 *       {@code otel.instrumentation.http.client.capture-response-headers} 配置中，
 *       使 HTTP Client Instrumentation 自动将该 Header 捕获为 Span 属性。
 * </ol>
 *
 * <p>这两个操作配合 SDK 层的 {@code PeerServiceSpanProcessor}，实现了完整的 Response Header
 * 回传机制，使 Client Span 能够精确设置 {@code peer.service} 属性。
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public class PeerServiceAutoConfigurationCustomizerProvider
    implements AutoConfigurationCustomizerProvider {

  private static final Logger logger =
      Logger.getLogger(PeerServiceAutoConfigurationCustomizerProvider.class.getName());

  /** Client 端捕获 Response Header 的配置 key */
  static final String CLIENT_CAPTURE_RESPONSE_HEADERS_KEY =
      "otel.instrumentation.http.client.capture-response-headers";

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    // 1. 在 Resource 构建完成后获取 service.name，注入到 PeerServiceResponseCustomizer
    autoConfiguration.addResourceCustomizer(
        (resource, config) -> {
          // 使用 AttributeKey 直接引用，避免依赖 semconv 包（在 agent classloader 中 semconv 类已被 shaded）
          String serviceName = resource.getAttribute(AttributeKey.stringKey("service.name"));
          if (serviceName != null && !serviceName.isEmpty()) {
            PeerServiceResponseCustomizer.setServiceName(serviceName);
          } else {
            // 尝试从配置中获取
            String configServiceName = config.getString("otel.service.name");
            if (configServiceName != null && !configServiceName.isEmpty()) {
              PeerServiceResponseCustomizer.setServiceName(configServiceName);
            } else {
              logger.warning(
                  "service.name not found in Resource or config, "
                      + "PeerServiceResponseCustomizer will not write x-otel-service-name header");
            }
          }
          return resource;
        });

    // 2. 将 x-otel-service-name 追加到 Client 端 capturedResponseHeaders 配置中
    autoConfiguration.addPropertiesCustomizer(
        config -> {
          Map<String, String> properties = new HashMap<>();
          String existingHeaders = config.getString(CLIENT_CAPTURE_RESPONSE_HEADERS_KEY);
          String headerToAdd = PeerServiceResponseCustomizer.SERVICE_NAME_HEADER;

          if (existingHeaders == null || existingHeaders.isEmpty()) {
            // 没有已有配置，直接设置
            properties.put(CLIENT_CAPTURE_RESPONSE_HEADERS_KEY, headerToAdd);
          } else {
            // 检查是否已包含，避免重复添加
            Set<String> headerSet = new LinkedHashSet<>(
                Arrays.asList(existingHeaders.split(",")));
            boolean alreadyContains = false;
            for (String header : headerSet) {
              if (header.trim().equalsIgnoreCase(headerToAdd)) {
                alreadyContains = true;
                break;
              }
            }
            if (!alreadyContains) {
              properties.put(
                  CLIENT_CAPTURE_RESPONSE_HEADERS_KEY,
                  existingHeaders + "," + headerToAdd);
            }
          }

          if (!properties.isEmpty()) {
            logger.info(
                "Added " + headerToAdd + " to " + CLIENT_CAPTURE_RESPONSE_HEADERS_KEY
                    + " for peer.service resolution");
          }
          return properties;
        });
  }
}
