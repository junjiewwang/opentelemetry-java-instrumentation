/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;

/**
 * Dubbo Client（Consumer）端 Filter，从 Response 的 attachment 中读取对端的
 * {@code x-otel-service-name} 并写入当前 Span 的 {@code peer.service} 属性。
 *
 * <p>与 gRPC 场景的 {@code PeerServiceGrpcClientInterceptor} 对应，本类负责 Dubbo 场景下
 * 从 Response attachment 中提取对端服务名，直接设置为 {@code peer.service} Span 属性。
 *
 * <p>通过 Dubbo SPI 机制（{@link Activate} 注解）自动注册到 consumer 端 Filter 链中，零侵入。
 * 使用 {@code order = 100} 确保在 OTel TracingFilter（order = -1）之后执行，
 * 此时 tracing span 已经创建完成。
 *
 * <p><b>关键设计：</b>不依赖 {@code ProtocolFilterWrapper} 的 {@code onResponse} 回调机制，
 * 而是在 {@link #invoke} 中通过 {@code AsyncRpcResult.thenApplyWithContext()} 自行注册回调。
 * 这种方式与 OTel 原生 {@code TracingFilter} 的做法一致，更加可靠。
 *
 * <p><b>Span 捕获：</b>在 {@link #invoke} 中提前捕获 {@code Span.current()} 并通过闭包传递
 * 给 {@code thenApplyWithContext} 回调。此时在 OTel TracingFilter 的
 * {@code context.makeCurrent()} scope 内，{@code Span.current()} 是 Client Span。
 *
 * <p><b>Attachment 读取时机：</b>
 * <ul>
 *   <li>异步场景：通过 {@code thenApplyWithContext()} 在 Future 完成时读取 attachment，
 *       此时传入的是底层 {@code RpcResult}，能正确读取到 Server 端设置的 attachment</li>
 *   <li>非 AsyncRpcResult 场景：直接从 Result 中读取 attachment</li>
 * </ul>
 *
 * <p><b>回调注册顺序：</b>由于我们的 Filter（order=100）在 OTel TracingFilter（order=-1）
 * 的内层，{@code ProtocolFilterWrapper} 先处理我们的 Filter，再处理 OTel 的。
 * 我们在 {@code invoke()} 中注册的 {@code thenApplyWithContext} 回调会先于
 * {@code ProtocolFilterWrapper} 为 OTel TracingFilter 注册的回调执行。
 * 而 OTel TracingFilter 自身在 {@code invoke()} 中通过 {@code future.whenComplete()}
 * 调用 {@code instrumenter.end()} 结束 Span。由于 {@code thenApplyWithContext} 修改的是
 * {@code resultFuture}（{@code CompletableFuture.thenApply}），而 OTel 监听的是
 * {@code RpcContext.getCompletableFuture()}（不同的 Future），两者互不干扰。
 * 对于同步调用（{@code future == null}），OTel 在 {@code invoke()} 返回后立即调用
 * {@code instrumenter.end()}，但我们的 {@code thenApplyWithContext} 在
 * {@code ProtocolFilterWrapper$1.invoke()} 内部已经执行完毕（对于已完成的 Future，
 * {@code thenApply} 立即同步执行），所以 Span 设置在 Span 结束之前完成。
 *
 * <p>优雅降级：如果 Response attachment 中没有 {@code x-otel-service-name}，
 * 则不设置属性，不影响正常功能。
 */
@Activate(group = {"consumer"}, order = 100)
public class PeerServiceDubboClientFilter implements Filter {

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    // 在 invoke 中提前捕获当前 Span。
    // 此时在 OTel TracingFilter 的 context.makeCurrent() scope 内，Span.current() 是 Client Span。
    Span clientSpan = Span.current();

    Result result = invoker.invoke(invocation);

    if (result instanceof AsyncRpcResult) {
      // 异步场景：通过 thenApplyWithContext 注册回调，在 Future 完成时读取 attachment。
      // thenApplyWithContext 传入的是底层 RpcResult，能正确读取到 Server 端设置的 attachment。
      // 对于同步调用（Future 已完成），thenApply 会立即同步执行。
      AsyncRpcResult asyncResult = (AsyncRpcResult) result;
      asyncResult.thenApplyWithContext(
          r -> {
            setPeerServiceFromAttachment(r, clientSpan);
            return r;
          });
    } else {
      // 非 AsyncRpcResult 场景（极少见），直接读取 attachment
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
