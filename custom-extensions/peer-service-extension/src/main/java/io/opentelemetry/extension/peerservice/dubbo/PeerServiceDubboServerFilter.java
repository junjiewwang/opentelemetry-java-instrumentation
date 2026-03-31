/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

import io.opentelemetry.extension.peerservice.common.PeerServiceResponseCustomizer;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
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
 * <p><b>关键设计：</b>不依赖 {@code ProtocolFilterWrapper} 的 {@code onResponse} 回调机制，
 * 而是在 {@link #invoke} 中通过 {@code AsyncRpcResult.thenApplyWithContext()} 自行注册回调。
 * 这种方式与 OTel 原生 {@code TracingFilter} 的做法一致，更加可靠：
 * <ul>
 *   <li>异步场景：通过 {@code thenApplyWithContext()} 在 Future 完成时设置 attachment，
 *       此时传入的是底层 {@code RpcResult}，attachment 会被正确序列化传输</li>
 *   <li>非 AsyncRpcResult 场景：直接在 Result 上设置 attachment</li>
 * </ul>
 *
 * <p>优雅降级：如果 service.name 未配置或获取失败，则不写入 attachment，不影响正常功能。
 */
@Activate(group = {"provider"}, order = 100)
public class PeerServiceDubboServerFilter implements Filter {

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    Result result = invoker.invoke(invocation);

    String serviceName = PeerServiceResponseCustomizer.getServiceName();
    if (serviceName == null || serviceName.isEmpty()) {
      return result;
    }

    if (result instanceof AsyncRpcResult) {
      // 异步场景：通过 thenApplyWithContext 注册回调，在 Future 完成时设置 attachment。
      // thenApplyWithContext 传入的是底层 RpcResult，attachment 会被正确序列化传输到 Client 端。
      // 对于同步调用（Future 已完成），thenApply 会立即同步执行。
      AsyncRpcResult asyncResult = (AsyncRpcResult) result;
      asyncResult.thenApplyWithContext(
          r -> {
            r.setAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, serviceName);
            return r;
          });
    } else {
      // 非 AsyncRpcResult 场景（极少见），直接设置 attachment
      result.setAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, serviceName);
    }

    return result;
  }
}
