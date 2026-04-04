/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.extension.peerservice.common;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 通用的 HTTP Response Header 清除器。
 *
 * <p>通过反射尝试多种常见的 HTTP 框架 API 来移除指定 header，
 * 避免引入任何框架编译期依赖。
 *
 * <p>采用"尽力而为"（best-effort）策略：如果当前 response 类型不支持任何已知的清除方式，
 * 则静默跳过，不影响正常功能（只是可能出现重复 header）。
 *
 * <p><b>支持的框架和清除方式（按优先级）：</b>
 * <ol>
 *   <li>{@code response.setHeader(name, null)} — Servlet API（Tomcat、Jetty、Spring MVC 等）</li>
 *   <li>{@code response.headers().set(name, value)} — Netty 4.0/4.1</li>
 *   <li>{@code response.getHeaders().remove(name)} — Jetty 12、Java HTTP Server</li>
 *   <li>{@code response.getResponseHeaders().remove(name)} — Undertow</li>
 * </ol>
 *
 * <p><b>性能优化：</b>反射探测结果缓存到 {@link ConcurrentHashMap}，每种 response 类型只探测一次。
 *
 * @see PeerServiceResponseCustomizer
 */
final class HttpResponseHeaderCleaner {

  private static final Logger logger =
      Logger.getLogger(HttpResponseHeaderCleaner.class.getName());

  /**
   * Header 清除策略缓存。key 为 response 对象的 Class，value 为对应的清除策略。
   * 使用 {@link ConcurrentHashMap} 保证线程安全，每种 response 类型只探测一次。
   */
  private static final ConcurrentHashMap<Class<?>, HeaderRemoveStrategy> STRATEGY_CACHE =
      new ConcurrentHashMap<>();

  /** 表示该 response 类型不支持任何已知的 header 清除方式 */
  private static final HeaderRemoveStrategy NOOP_STRATEGY = (response, headerName) -> {};

  private HttpResponseHeaderCleaner() {}

  /**
   * 尝试从 response 对象中移除指定的 header。
   *
   * <p>如果 response 类型不支持任何已知的清除方式，则静默跳过。
   *
   * @param response HTTP response 对象（泛型，可以是任何框架的 response）
   * @param headerName 要移除的 header 名称
   */
  static void tryRemoveHeader(Object response, String headerName) {
    if (response == null) {
      return;
    }
    HeaderRemoveStrategy strategy =
        STRATEGY_CACHE.computeIfAbsent(response.getClass(), clazz -> detectStrategy(clazz));
    try {
      strategy.remove(response, headerName);
    } catch (Exception e) {
      // 尽力而为，清除失败不影响正常功能
      logger.log(Level.FINE, "Failed to remove header: " + headerName, e);
    }
  }

  /**
   * 探测 response 对象支持的 header 清除策略。
   *
   * <p>按优先级依次尝试以下方式：
   * <ol>
   *   <li>直接调用 {@code setHeader(String, String)} 方法（Servlet API）</li>
   *   <li>通过 {@code headers()} 获取 headers 对象，再调用其 {@code remove} 方法（Netty）</li>
   *   <li>通过 {@code getHeaders()} 获取 headers 对象，再调用其 {@code remove} 方法（Jetty 12、Java HTTP Server）</li>
   *   <li>通过 {@code getResponseHeaders()} 获取 headers 对象，再调用其 {@code remove} 方法（Undertow）</li>
   * </ol>
   */
  private static HeaderRemoveStrategy detectStrategy(Class<?> responseClass) {
    // 策略1：Servlet API — response.setHeader(name, "")
    // HttpServletResponse.setHeader 会覆盖已有的同名 header
    HeaderRemoveStrategy strategy = trySetHeaderStrategy(responseClass);
    if (strategy != null) {
      logger.log(Level.FINE, "Using setHeader strategy for {0}", responseClass.getName());
      return strategy;
    }

    // 策略2：Netty — response.headers().remove(name)
    strategy = tryHeadersAccessorStrategy(responseClass, "headers");
    if (strategy != null) {
      logger.log(Level.FINE, "Using headers().remove strategy for {0}", responseClass.getName());
      return strategy;
    }

    // 策略3：Jetty 12 / Java HTTP Server — response.getHeaders().remove(name)
    strategy = tryHeadersAccessorStrategy(responseClass, "getHeaders");
    if (strategy != null) {
      logger.log(
          Level.FINE, "Using getHeaders().remove strategy for {0}", responseClass.getName());
      return strategy;
    }

    // 策略4：Undertow — response.getResponseHeaders().remove(name)
    strategy = tryHeadersAccessorStrategy(responseClass, "getResponseHeaders");
    if (strategy != null) {
      logger.log(
          Level.FINE,
          "Using getResponseHeaders().remove strategy for {0}",
          responseClass.getName());
      return strategy;
    }

    logger.log(
        Level.FINE,
        "No header remove strategy found for {0}, will use NOOP",
        responseClass.getName());
    return NOOP_STRATEGY;
  }

  /**
   * 尝试使用 {@code setHeader(String, String)} 方法的策略。
   * 适用于 Servlet API（javax.servlet.http.HttpServletResponse / jakarta.servlet.http.HttpServletResponse）。
   *
   * <p>Servlet 的 {@code setHeader(name, value)} 会覆盖已有的同名 header。
   * 我们先调用 {@code setHeader(name, "")} 设置为空值，后续 {@code appendHeader} 会再追加正确的值。
   * 但空值 header 仍然存在，所以更好的方式是：如果有 {@code containsHeader} 方法，
   * 先检查是否存在，再用 {@code setHeader} 覆盖。
   *
   * <p>注意：这里不能直接设置为 null，因为某些 Servlet 容器不支持 null 值。
   */
  private static HeaderRemoveStrategy trySetHeaderStrategy(Class<?> responseClass) {
    try {
      Method setHeaderMethod =
          responseClass.getMethod("setHeader", String.class, String.class);
      return (response, headerName) -> {
        // 使用 setHeader 覆盖为空字符串，后续 appendHeader 会追加正确的值
        // 最终效果：只有一个 header（我们设置的值）
        // 注意：这里设置空字符串而不是 null，因为某些容器不支持 null
        setHeaderMethod.invoke(response, headerName, "");
      };
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  /**
   * 尝试通过 headers 访问器方法获取 headers 对象，再调用其 {@code remove} 方法。
   * 适用于 Netty（{@code headers()}）、Jetty 12（{@code getHeaders()}）、
   * Undertow（{@code getResponseHeaders()}）等。
   *
   * @param responseClass response 对象的类
   * @param accessorMethodName headers 访问器方法名（如 "headers"、"getHeaders"、"getResponseHeaders"）
   */
  private static HeaderRemoveStrategy tryHeadersAccessorStrategy(
      Class<?> responseClass, String accessorMethodName) {
    try {
      Method accessorMethod = responseClass.getMethod(accessorMethodName);
      Class<?> headersClass = accessorMethod.getReturnType();

      // 尝试找 remove(String) 方法
      Method removeMethod = findRemoveMethod(headersClass);
      if (removeMethod != null) {
        return (response, headerName) -> {
          Object headers = accessorMethod.invoke(response);
          if (headers != null) {
            removeMethod.invoke(headers, headerName);
          }
        };
      }
    } catch (NoSuchMethodException e) {
      // 该 response 类型没有此访问器方法
    }
    return null;
  }

  /**
   * 在 headers 对象的类中查找 remove 方法。
   * 按优先级尝试：{@code remove(String)}、{@code remove(Object)}。
   */
  private static Method findRemoveMethod(Class<?> headersClass) {
    // 优先尝试 remove(String)
    try {
      return headersClass.getMethod("remove", String.class);
    } catch (NoSuchMethodException e) {
      // 继续尝试
    }

    // 尝试 remove(Object) — 适用于 Map 接口（如 Java HTTP Server 的 Headers extends HashMap）
    try {
      return headersClass.getMethod("remove", Object.class);
    } catch (NoSuchMethodException e) {
      // 继续尝试
    }

    return null;
  }

  /** Header 清除策略的函数式接口 */
  @FunctionalInterface
  interface HeaderRemoveStrategy {
    void remove(Object response, String headerName) throws Exception;
  }
}
