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
 * gRPC Client 端 Interceptor，从 Response Headers 中读取 {@code x-otel-service-name}
 * 并设置 {@code peer.service} 属性到当前 gRPC Client Span。
 *
 * <p><b>设计背景：</b>之前尝试了两种方案均失败：
 * <ol>
 *   <li>独立 interceptor + {@code Span.current()} — 由于 ClassLoader 隔离，
 *       {@code Span.current()} 拿到的是 HTTP Server Span 而非 gRPC Client Span</li>
 *   <li>在 OTel 原生的 {@code TracingClientCallListener} 上织入 Advice —
 *       由于 {@code TracingClientCallListener} 是 helper class，被
 *       {@code HelperInjector.isInjectedClass()} 无条件忽略，Advice 无法织入</li>
 * </ol>
 *
 * <p><b>当前方案：</b>通过 {@link PeerServiceGrpcClientBuilderInstrumentation} 在
 * {@code ManagedChannelBuilder.build()} 时将本 interceptor 添加到 interceptor 列表末尾。
 * gRPC interceptor 链的执行顺序保证了：
 * <ul>
 *   <li>OTel 的 {@code TracingClientInterceptor}（索引 0）先执行 {@code interceptCall()}，
 *       创建 context 和 span</li>
 *   <li>本 interceptor 后执行 {@code interceptCall()}，包装 responseListener</li>
 *   <li>在 {@code start()} 中，OTel 的 {@code TracingClientCall} 会用
 *       {@code TracingClientCallListener} 包装我们的 listener</li>
 *   <li>在 {@code onHeaders} 回调中，{@code TracingClientCallListener.onHeaders()} 先执行
 *       {@code context.makeCurrent()}，然后调用 {@code delegate().onHeaders()} —
 *       即我们的 listener</li>
 *   <li>此时 {@code Span.current()} 就是 gRPC Client Span ✅</li>
 * </ul>
 *
 * <p>优雅降级：如果 Response Metadata 中没有 {@code x-otel-service-name}，
 * 则不设置属性，不影响正常功能。
 *
 * @see PeerServiceGrpcClientBuilderInstrumentation
 * @see PeerServiceGrpcServerInterceptor
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
        super.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RESP>(
                responseListener) {
              @Override
              public void onHeaders(Metadata responseHeaders) {
                String peerServiceName = responseHeaders.get(SERVICE_NAME_METADATA_KEY);
                if (peerServiceName != null && !peerServiceName.isEmpty()) {
                  // 此时 Span.current() 是 gRPC Client Span，因为 OTel 的
                  // TracingClientCallListener.onHeaders() 已经执行了 context.makeCurrent()
                  Span.current().setAttribute("peer.service", peerServiceName);
                }
                super.onHeaders(responseHeaders);
              }
            },
            headers);
      }
    };
  }
}
