/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.dubbo;

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
 * <p><b>关键设计：</b>只使用 {@code Result} 接口的 {@code setAttachment()} 方法，
 * 不引用 {@code AsyncRpcResult} 等具体实现类，确保同时兼容 Dubbo 2.7.x 和 3.x。
 * 在 Dubbo 3.x 中 {@code AsyncRpcResult.setAttachment()} 会委托给底层 {@code AppResponse}，
 * 对于同步调用（Future 已完成）能正确设置 attachment 并序列化传输到 Client 端。
 *
 * <p>优雅降级：如果 service.name 未配置或获取失败，则不写入 attachment，不影响正常功能。
 */
@Activate(group = {"provider"}, order = 100)
public class PeerServiceDubboServerFilter implements Filter {

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) {
    Result result = invoker.invoke(invocation);

    String serviceName = PeerServiceResponseCustomizer.getServiceName();
    if (serviceName != null && !serviceName.isEmpty()) {
      // 直接通过 Result 接口的 setAttachment 方法设置 attachment。
      // 在 Dubbo 2.7.x 和 3.x 中，AsyncRpcResult.setAttachment() 都会委托给底层的
      // AppResponse/RpcResult，attachment 会被正确序列化传输到 Client 端。
      result.setAttachment(PeerServiceResponseCustomizer.SERVICE_NAME_HEADER, serviceName);
    }

    return result;
  }
}
