/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.logging.simple;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.bootstrap.InternalLogger;
import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.LoggerFactory;

@AutoService(LoggingCustomizer.class)
public final class Slf4jSimpleLoggingCustomizer implements LoggingCustomizer {

  // org.slf4j package name in the constants will be shaded too
  private static final String SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY =
      "org.slf4j.simpleLogger.showDateTime";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY =
      "org.slf4j.simpleLogger.dateTimeFormat";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT =
      "'[otel.javaagent 'yyyy-MM-dd HH:mm:ss:SSS Z']'";
  private static final String SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY =
      "org.slf4j.simpleLogger.defaultLogLevel";
  private static final String SIMPLE_LOGGER_PREFIX = "org.slf4j.simpleLogger.log.";

  // 默认日志文件输出配置
  private static final String SIMPLE_LOGGER_LOG_FILE_PROPERTY = "org.slf4j.simpleLogger.logFile";
  private static final String DEFAULT_LOG_FILE_PATH = "/tmp/opentelemetry/agent.log";

  @Override
  public String name() {
    return "simple";
  }

  @Override
  public void init(EarlyInitAgentConfig earlyConfig) {
    // 设置默认日志文件输出（用户未配置时生效）
    String logFilePath = earlyConfig.getString("otel.javaagent.logging.file.path");
    if (logFilePath == null || logFilePath.isEmpty()) {
      logFilePath = DEFAULT_LOG_FILE_PATH;
    }
    if (ensureLogDirectoryExists(logFilePath)) {
      setSystemPropertyDefault(SIMPLE_LOGGER_LOG_FILE_PROPERTY, logFilePath);
    }

    setSystemPropertyDefault(SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY, "true");
    setSystemPropertyDefault(
        SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY, SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT);

    if (earlyConfig.getBoolean("otel.javaagent.debug", false)) {
      setSystemPropertyDefault(SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY, "DEBUG");
      setSystemPropertyDefault(SIMPLE_LOGGER_PREFIX + "okhttp3.internal.http2", "INFO");
      setSystemPropertyDefault(
          SIMPLE_LOGGER_PREFIX + "okhttp3.internal.concurrent.TaskRunner", "INFO");
    }

    // trigger loading the provider from the agent CL
    LoggerFactory.getILoggerFactory();

    InternalLogger.initialize(Slf4jSimpleLogger::create);
  }

  @Override
  @SuppressWarnings("SystemOut")
  public void onStartupFailure(Throwable throwable) {
    // not sure if we have a log manager here, so just print
    System.err.println("OpenTelemetry Javaagent failed to start");
    throwable.printStackTrace();
  }

  @Override
  public void onStartupSuccess() {}

  private static void setSystemPropertyDefault(String property, String value) {
    if (System.getProperty(property) == null) {
      System.setProperty(property, value);
    }
  }

  /**
   * 确保日志文件所在目录存在，如果不存在则创建。
   *
   * @param logFilePath 日志文件路径
   * @return 如果目录存在或创建成功返回 true，否则返回 false
   */
  @SuppressWarnings("SystemOut")
  private static boolean ensureLogDirectoryExists(String logFilePath) {
    try {
      Path logDir = Paths.get(logFilePath).getParent();
      if (logDir != null && !Files.exists(logDir)) {
        Files.createDirectories(logDir);
      }
      return true;
    } catch (IOException e) {
      // 目录创建失败，回退到 stderr 输出
      System.err.println(
          "[otel.javaagent] Failed to create log directory for " + logFilePath + ": " + e.getMessage());
      return false;
    }
  }
}
