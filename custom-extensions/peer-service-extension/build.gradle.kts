plugins {
  id("otel.javaagent-instrumentation")
}

val grpcVersion = "1.6.0"
val dubboVersion = "2.7.0"

dependencies {
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")

  // gRPC 依赖，用于 peer service gRPC interceptor 注入
  library("io.grpc:grpc-core:$grpcVersion")

  // Dubbo 依赖，用于 peer service Dubbo Filter 注入
  library("org.apache.dubbo:dubbo:$dubboVersion")

  annotationProcessor("com.google.auto.service:auto-service")
  compileOnly("com.google.auto.service:auto-service-annotations")
}
