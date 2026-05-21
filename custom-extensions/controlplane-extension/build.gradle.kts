plugins {
  id("otel.sdk-extension")
}

dependencies {
  // Control Plane Extension - 控制平面扩展，用于动态配置管理
  // 通过 SDK SPI (AutoConfigurationCustomizerProvider) 自动注册，支持：
  // 1. 内嵌到 javaagent jar 中一体化打包
  // 2. 作为独立 extension jar 通过 -Dotel.javaagent.extensions 加载
  implementation("io.opentelemetry:opentelemetry-sdk-extension-controlplane")
}
