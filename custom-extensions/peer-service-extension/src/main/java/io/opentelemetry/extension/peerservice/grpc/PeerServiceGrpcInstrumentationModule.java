/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.grpc;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

/**
 * gRPC Peer Service InstrumentationModule，将 Server 端和 Client 端的 bytecode weaving 组合在一起。
 *
 * <p>通过 {@link AutoService} SPI 机制自动注册到 agent 中。当目标应用的 classpath 中存在
 * gRPC 相关类时，muzzle 检查通过后自动生效；如果没有 gRPC 依赖，则自动跳过，不影响 HTTP 场景。
 *
 * <p>模块名为 {@code peer-service-grpc}，可通过
 * {@code otel.instrumentation.peer-service-grpc.enabled=false} 配置禁用。
 */
@AutoService(InstrumentationModule.class)
public class PeerServiceGrpcInstrumentationModule extends InstrumentationModule {

  public PeerServiceGrpcInstrumentationModule() {
    super("peer-service-grpc");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return asList(
        new PeerServiceGrpcServerBuilderInstrumentation(),
        new PeerServiceGrpcClientBuilderInstrumentation());
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.startsWith("io.opentelemetry.extension.peerservice.");
  }
}
