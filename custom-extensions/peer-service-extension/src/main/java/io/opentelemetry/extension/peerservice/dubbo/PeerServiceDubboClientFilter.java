/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;
import org.apache.dubbo.common.extension.Activate;
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
 * <p><b>关键设计：</b>attachment 的读取在 {@link #onResponse} 中完成，而非 {@link #invoke} 中。
 * 这是因为 Dubbo 2.7 中 {@code invoker.invoke()} 返回的是 {@code AsyncRpcResult}，
 * 在其上调用 {@code getAttachment()} 无法获取到 Server 端设置的 attachment。
 * Dubbo 框架的 {@code ProtocolFilterWrapper} 会在 {@code AsyncRpcResult} 完成后，
 * 将底层的 {@code RpcResult} 传入 {@code onResponse} 回调，此时才能正确读取 attachment。
 *
 * <p>Span 的获取：在 {@link #invoke} 中提前捕获 {@code Span.current()} 并存入 ThreadLocal，
 * 在 {@link #onResponse} 中取出使用。对于同步调用（绝大多数 Dubbo 场景），{@code onResponse}
 * 在同一线程中执行，ThreadLocal 有效。对于异步调用，{@code onResponse} 可能在不同线程执行，
 * 此时降级为使用 {@code Span.current()}（在 OTel TracingFilter 的 scope 内可能有效）。
 *
 * <p>优雅降级：如果 Response attachment 中没有 {@code x-otel-service-name}，
 * 则不设置属性，不影响正常功能。
 */
@Activate(group = {"consumer"}, order = 100)
public class PeerServiceDubboClientFilter implements Filter {

  /**
   * ThreadLocal 用于在 invoke 和 onResponse 之间传递 Span 引用。
   * Dubbo 2.7.0 的 Invocation 接口不支持 setAttachment(String, Object)，
   * 因此使用 ThreadLocal 作为替代方案。
   */
  private static final ThreadLocal<Span> CLIENT_SPAN_HOLDER = new ThreadLocal<>();

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    // 在 invoke 中提前捕获当前 Span，存入 ThreadLocal。
    // 此时在 OTel TracingFilter 的 context.makeCurrent() scope 内，Span.current() 是 Client Span。
    CLIENT_SPAN_HOLDER.set(Span.current());
    try {
      return invoker.invoke(invocation);
    } catch (Throwable t) {
      CLIENT_SPAN_HOLDER.remove();
      throw t;
    }
  }

  /**
   * 在 Response 返回时读取 attachment 并设置 {@code peer.service}。
   *
   * <p>Dubbo 框架的 {@code ProtocolFilterWrapper} 会自动调用此方法：
   * <ul>
   *   <li>同步场景：直接调用 {@code filter.onResponse(result, invoker, invocation)}，
   *       此时仍在同一线程，ThreadLocal 中的 Span 有效</li>
   *   <li>异步场景：通过 {@code AsyncRpcResult.thenApplyWithContext()} 在 Future 完成时调用，
   *       可能在不同线程，ThreadLocal 中的 Span 可能无效，降级为 {@code Span.current()}</li>
   * </ul>
   * 此时传入的 {@code result} 是底层的 {@code RpcResult}（非 {@code AsyncRpcResult}），
   * 能正确读取到 Server 端设置的 attachment。
   */
  @CanIgnoreReturnValue
  @Override
  public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
    try {
      String peerServiceName =
          result.getAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER);
      if (peerServiceName != null && !peerServiceName.isEmpty()) {
        // 优先使用 ThreadLocal 中提前捕获的 Span（同步场景下有效）
        Span span = CLIENT_SPAN_HOLDER.get();
        if (span == null || !span.getSpanContext().isValid()) {
          // 降级为 Span.current()（异步场景下可能有效）
          span = Span.current();
        }
        if (span.getSpanContext().isValid()) {
          span.setAttribute("peer.service", peerServiceName);
        }
      }
    } finally {
      CLIENT_SPAN_HOLDER.remove();
    }
    return result;
  }
}
