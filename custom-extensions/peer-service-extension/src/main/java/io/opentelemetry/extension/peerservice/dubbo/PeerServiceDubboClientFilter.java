/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;
import java.util.concurrent.CompletableFuture;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;

/**
 * Dubbo Client（Consumer）端 Filter，从 Response 的 attachment 中读取对端的
 * {@code x-otel-service-name} 并写入当前 Span 的 {@code peer.service} 属性。
 *
 * <p>与 gRPC 场景的 {@code PeerServiceGrpcClientCallListenerInstrumentation} 对应，本类负责 Dubbo 场景下
 * 从 Response attachment 中提取对端服务名，直接设置为 {@code peer.service} Span 属性。
 *
 * <p>通过 Dubbo SPI 机制（{@link Activate} 注解）自动注册到 consumer 端 Filter 链中，零侵入。
 * 使用 {@code order = 100} 确保在 OTel TracingFilter（order = -1）之后执行，
 * 此时 tracing span 已经创建完成。
 *
 * <p><b>关键设计：</b>只使用 {@code Result} 接口和 {@code RpcContext} 的标准 API，
 * 不引用 {@code AsyncRpcResult} 等具体实现类，确保同时兼容 Dubbo 2.7.x 和 3.x。
 * 这与 OTel 原生 {@code TracingFilter} 的做法一致——通过
 * {@code RpcContext.getCompletableFuture()} 处理异步场景。
 *
 * <p><b>Span 捕获：</b>在 {@link #invoke} 中提前捕获 {@code Span.current()} 并通过闭包传递。
 * 此时在 OTel TracingFilter 的 {@code context.makeCurrent()} scope 内，
 * {@code Span.current()} 是 Client Span。
 *
 * <p><b>同步/异步处理：</b>
 * <ul>
 *   <li>同步场景：{@code RpcContext.getCompletableFuture()} 为 null 或已完成，
 *       直接从 {@code Result.getAttachment()} 读取 attachment</li>
 *   <li>异步场景：通过 {@code future.whenComplete()} 注册回调，在 Future 完成时
 *       从 {@code Result.getAttachment()} 读取 attachment 并设置 {@code peer.service}</li>
 * </ul>
 *
 * <p>优雅降级：如果 Response attachment 中没有 {@code x-otel-service-name}，
 * 则不设置属性，不影响正常功能。
 */
@Activate(group = {"consumer"}, order = 100)
@SuppressWarnings("deprecation") // RpcContext.getContext() 在 Dubbo 3.x 中已废弃但仍可用
public class PeerServiceDubboClientFilter implements Filter {

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    // 在 invoke 中提前捕获当前 Span。
    // 此时在 OTel TracingFilter 的 context.makeCurrent() scope 内，Span.current() 是 Client Span。
    Span clientSpan = Span.current();

    Result result = invoker.invoke(invocation);

    // 与 OTel 原生 TracingFilter 一致，通过 RpcContext.getCompletableFuture() 判断异步场景
    CompletableFuture<Object> future = RpcContext.getContext().getCompletableFuture();
    if (future != null) {
      // 异步场景：在 Future 完成时读取 attachment
      future.whenComplete(
          (value, throwable) -> setPeerServiceFromAttachment(result, clientSpan));
    } else {
      // 同步场景：直接读取 attachment
      setPeerServiceFromAttachment(result, clientSpan);
    }

    return result;
  }

  /**
   * 从 Result 的 attachment 中读取 peer service name 并设置到 Span 上。
   */
  private static void setPeerServiceFromAttachment(Result result, Span span) {
    String peerServiceName =
        result.getAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER);
    if (peerServiceName != null && !peerServiceName.isEmpty() && span.getSpanContext().isValid()) {
      span.setAttribute("peer.service", peerServiceName);
    }
  }
}
