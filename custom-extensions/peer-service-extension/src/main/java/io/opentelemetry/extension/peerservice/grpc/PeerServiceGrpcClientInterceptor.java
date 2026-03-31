/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;

/**
 * gRPC Client 端 Interceptor，从 Response Headers 中读取对端的 {@code x-otel-service-name}
 * 并写入当前 Span 的 {@code peer.service} 属性。
 *
 * <p>与 HTTP 场景的 {@code capturedResponseHeaders} 配置对应，本类负责 gRPC 场景下
 * 从 Response Metadata 中提取对端服务名，直接设置为 {@code peer.service} Span 属性。
 *
 * <p>通过 agent bytecode weaving 自动注入到 {@code ManagedChannelBuilder.build()} 中，零侵入。
 *
 * <p><b>关键设计：Span 在 {@code start()} 中捕获，而非 {@code interceptCall()} 中。</b>
 * 由于我们的 Advice 和 OTel 的 Advice 都在 {@code ManagedChannelBuilder.build()} 的
 * {@code @OnMethodEnter} 上注入，两者的执行顺序不确定，导致 interceptor 在列表中的位置不确定。
 * 如果在 {@code interceptCall()} 中捕获 {@code Span.current()}，当我们的 interceptor
 * 先于 OTel 执行时，OTel 还未创建 Client Span，捕获到的是 parent Span。
 *
 * <p>而 {@code start()} 的调用链是确定的：OTel 的 {@code TracingClientCall.start()} 中
 * 会先调用 {@code context.makeCurrent()}，然后调用 {@code super.start()}（即我们的
 * {@code ForwardingClientCall.start()}），此时 {@code Span.current()} 一定是 Client Span。
 * 无论 interceptor 在列表中的位置如何，{@code start()} 中的 Span 捕获都是正确的。
 *
 * <p>优雅降级：如果 Response Metadata 中没有 {@code x-otel-service-name}，
 * 则不设置属性，不影响正常功能。
 */
public final class PeerServiceGrpcClientInterceptor implements ClientInterceptor {

  private static final Metadata.Key<String> SERVICE_NAME_METADATA_KEY =
      Metadata.Key.of(
          PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <REQ, RESP> ClientCall<REQ, RESP> interceptCall(
      MethodDescriptor<REQ, RESP> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<REQ, RESP>(
        next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RESP> responseListener, Metadata headers) {
        // 在 start() 中捕获 Span，而非 interceptCall() 中。
        // 无论 interceptor 顺序如何，此处一定在 OTel TracingClientCall.start() 的
        // context.makeCurrent() scope 内，Span.current() 是 Client Span。
        Span clientSpan = Span.current();

        super.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RESP>(
                responseListener) {
              @Override
              public void onHeaders(Metadata responseHeaders) {
                String peerServiceName = responseHeaders.get(SERVICE_NAME_METADATA_KEY);
                if (peerServiceName != null && !peerServiceName.isEmpty()) {
                  clientSpan.setAttribute("peer.service", peerServiceName);
                }
                super.onHeaders(responseHeaders);
              }
            },
            headers);
      }
    };
  }
}
