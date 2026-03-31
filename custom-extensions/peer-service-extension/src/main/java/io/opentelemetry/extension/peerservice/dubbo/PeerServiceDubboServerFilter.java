/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;

/**
 * Dubbo Server（Provider）端 Filter，在 Response 的 attachment 中回传 {@code x-otel-service-name}。
 *
 * <p>与 HTTP 场景的 {@link PeerServiceResponseCustomizer} 和 gRPC 场景的
 * {@code PeerServiceGrpcServerInterceptor} 对应，本类负责 Dubbo 场景下将本服务的
 * {@code service.name} 写入 Dubbo Response attachment，使得 Client（Consumer）端的
 * {@link PeerServiceDubboClientFilter} 能够读取并设置 {@code peer.service} 属性。
 *
 * <p>通过 Dubbo SPI 机制（{@link Activate} 注解）自动注册到 provider 端 Filter 链中，零侵入。
 * 使用 {@code order = 100} 确保在 OTel TracingFilter（order = -1）之后执行，
 * 此时 tracing span 已经创建完成。
 *
 * <p><b>关键设计：</b>attachment 的写入在 {@link #onResponse} 中完成，而非 {@link #invoke} 中。
 * 这是因为 Dubbo 2.7 中 {@code invoker.invoke()} 返回的是 {@code AsyncRpcResult}，
 * 在其上调用 {@code setAttachment()} 不会被序列化传输到 Client 端。
 * Dubbo 框架的 {@code ProtocolFilterWrapper} 会在 {@code AsyncRpcResult} 完成后，
 * 将底层的 {@code RpcResult} 传入 {@code onResponse} 回调，此时设置的 attachment
 * 才会被正确序列化传输。
 *
 * <p>优雅降级：如果 service.name 未配置或获取失败，则不写入 attachment，不影响正常功能。
 */
@Activate(group = {"provider"}, order = 100)
public class PeerServiceDubboServerFilter implements Filter {

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    // 直接透传调用，attachment 的写入在 onResponse 中完成
    return invoker.invoke(invocation);
  }

  /**
   * 在 Response 返回时写入 service.name attachment。
   *
   * <p>Dubbo 框架的 {@code ProtocolFilterWrapper} 会自动调用此方法：
   * <ul>
   *   <li>同步场景：直接调用 {@code filter.onResponse(result, invoker, invocation)}</li>
   *   <li>异步场景：通过 {@code AsyncRpcResult.thenApplyWithContext()} 在 Future 完成时调用</li>
   * </ul>
   * 此时传入的 {@code result} 是底层的 {@code RpcResult}（非 {@code AsyncRpcResult}），
   * 在其上设置的 attachment 会被 Dubbo 协议正确序列化传输到 Client 端。
   */
  @CanIgnoreReturnValue
  @Override
  public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
    String serviceName = PeerServiceResponseCustomizer.getServiceName();
    if (serviceName != null && !serviceName.isEmpty()) {
      result.setAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, serviceName);
    }
    return result;
  }
}
