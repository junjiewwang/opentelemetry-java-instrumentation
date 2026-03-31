/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;

/**
 * gRPC Server 端 Interceptor，在 Response Headers 中回传 {@code x-otel-service-name}。
 *
 * <p>与 HTTP 场景的 {@link PeerServiceResponseCustomizer} 对应，本类负责 gRPC 场景下
 * 将本服务的 {@code service.name} 写入 gRPC Response Metadata，使得 Client 端的
 * {@link PeerServiceGrpcClientInterceptor} 能够读取并设置 {@code peer.service} 属性。
 *
 * <p>通过 agent bytecode weaving 自动注入到 {@code ServerBuilder.build()} 中，零侵入。
 *
 * <p>优雅降级：如果 service.name 未配置或获取失败，则不写入 metadata，不影响正常功能。
 */
public final class PeerServiceGrpcServerInterceptor implements ServerInterceptor {

  private static final Metadata.Key<String> SERVICE_NAME_METADATA_KEY =
      Metadata.Key.of(
          PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <REQ, RESP> ServerCall.Listener<REQ> interceptCall(
      ServerCall<REQ, RESP> call, Metadata headers, ServerCallHandler<REQ, RESP> next) {
    return next.startCall(
        new ForwardingServerCall.SimpleForwardingServerCall<REQ, RESP>(call) {
          @Override
          public void sendHeaders(Metadata responseHeaders) {
            String name = PeerServiceResponseCustomizer.getServiceName();
            if (name != null && !name.isEmpty()) {
              responseHeaders.put(SERVICE_NAME_METADATA_KEY, name);
            }
            super.sendHeaders(responseHeaders);
          }
        },
        headers);
  }
}
