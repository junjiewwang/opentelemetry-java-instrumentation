plugins {
  id("otel.sdk-extension")
  id("com.gradleup.shadow")
}

dependencies {
  // Control Plane Extension - 控制平面扩展，用于动态配置管理
  // 通过 SDK SPI (AutoConfigurationCustomizerProvider) 自动注册，支持：
  // 1. 内嵌到 javaagent jar 中一体化打包（默认 jar 任务）
  // 2. 作为独立 extension jar 通过 -Dotel.javaagent.extensions 加载（shadowJar 任务）
  implementation("io.opentelemetry:opentelemetry-sdk-extension-controlplane")
}

// 独立 extension fat jar：包含所有 runtime 依赖，可通过 -Dotel.javaagent.extensions 加载
tasks.shadowJar {
  archiveClassifier.set("standalone")
  mergeServiceFiles()

  // 排除 agent extension classloader 已提供的类，避免冲突
  dependencies {
    exclude(dependency("io.opentelemetry:opentelemetry-sdk"))
    exclude(dependency("io.opentelemetry:opentelemetry-sdk-common"))
    exclude(dependency("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi"))
    exclude(dependency("io.opentelemetry:opentelemetry-api"))
    exclude(dependency("io.opentelemetry:opentelemetry-context"))
    exclude(dependency("io.opentelemetry:opentelemetry-sdk-trace"))
    exclude(dependency("io.opentelemetry:opentelemetry-sdk-metrics"))
    exclude(dependency("io.opentelemetry:opentelemetry-sdk-logs"))
  }
}
