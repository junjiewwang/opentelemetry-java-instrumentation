# Controlplane Extension 使用指南

## 概述

Controlplane Extension 是一个控制平面扩展模块，基于 OpenTelemetry SDK 的 `opentelemetry-sdk-extension-controlplane` 实现，用于支持通过 gRPC 控制平面对 Agent 进行动态配置管理（如动态采样率调整、远程配置下发等）。

该模块已从 `javaagent-tooling` 中解耦，作为独立的 SDK Extension 放置在 `custom-extensions/controlplane-extension` 下，支持以下两种使用方式：

- **内嵌模式**：与 javaagent 一体化打包，开箱即用
- **独立模式**：作为外部 extension jar 加载，方便扩展到其他版本或开源版本

## 模块结构

```
custom-extensions/
├── peer-service-extension/          # peer.service 自动补全扩展
├── controlplane-extension/          # 控制平面扩展（本模块）
│   └── build.gradle.kts             # 使用 otel.sdk-extension 插件
└── CONTROLPLANE_EXTENSION.md        # 本文档
```

### 构建配置

`controlplane-extension/build.gradle.kts`：

```kotlin
plugins {
  id("otel.sdk-extension")
}

dependencies {
  implementation("io.opentelemetry:opentelemetry-sdk-extension-controlplane")
}
```

- 使用 `otel.sdk-extension` 插件，标识这是一个纯 SDK 扩展（无字节码织入）
- 版本由项目根级 `dependencyManagement` BOM 统一管控，无需显式指定

---

## 使用方式

### 方式一：内嵌到 javaagent jar（默认，推荐）

**适用场景**：自定义发行版，需要 controlplane 能力且希望一个 jar 搞定。

当前项目已默认启用此方式。在 `javaagent/build.gradle.kts` 中通过以下配置将 controlplane-extension 打包进最终的 `opentelemetry-javaagent.jar`：

```kotlin
// custom extensions - 手动添加不在 :instrumentation 下的自定义扩展
javaagentDependencies.add(javaagentLibs.name, project(":custom-extensions:peer-service-extension"))
javaagentDependencies.add(javaagentLibs.name, project(":custom-extensions:controlplane-extension"))
```

**构建命令**：

```bash
./gradlew :javaagent:assemble
```

产出物为 `javaagent/build/libs/opentelemetry-javaagent-<version>.jar`，内含 controlplane 扩展。

**启动应用**：

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -jar myapp.jar
```

controlplane 通过 SPI 自动注册，无需额外配置即可生效。

---

### 方式二：作为独立 extension jar 加载

**适用场景**：

- 使用开源原版 `opentelemetry-javaagent.jar`，不想修改其源码
- 按需启用 controlplane，某些环境不需要此能力
- 需要在不同版本的 agent 上灵活搭配

#### 步骤 1：构建独立 extension jar

```bash
./gradlew :custom-extensions:controlplane-extension:jar
```

产出物位于：

```
custom-extensions/controlplane-extension/build/libs/controlplane-extension-<version>.jar
```

#### 步骤 2：通过 `-Dotel.javaagent.extensions` 加载

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/path/to/controlplane-extension.jar \
     -jar myapp.jar
```

**加载多个 extension**（用逗号分隔）：

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/path/to/controlplane-extension.jar,/path/to/other-extension.jar \
     -jar myapp.jar
```

也可以指定一个目录，agent 会自动加载目录下所有 jar：

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/path/to/extensions/ \
     -jar myapp.jar
```

#### 步骤 3：验证加载成功

启动时添加 `-Dotel.javaagent.debug=true`，在日志中搜索 `controlplane` 相关输出，确认扩展已被 SPI 发现并注册。

---

### 方式三：从内嵌模式中移除（仅外部加载）

如果希望默认构建的 javaagent jar **不包含** controlplane（轻量化打包），只需注释或删除 `javaagent/build.gradle.kts` 中的对应行：

```kotlin
// javaagentDependencies.add(javaagentLibs.name, project(":custom-extensions:controlplane-extension"))
```

然后在需要 controlplane 的环境中通过方式二（独立 extension jar）加载即可。

---

## 技术原理

### 自动注册机制

controlplane 模块完全通过 SDK SPI（`AutoConfigurationCustomizerProvider`）自动注册，项目中**无任何 Java 代码显式引用**其类。agent 启动时的加载流程如下：

```
Agent 启动
  → ExtensionClassLoader 加载 extension jar（内嵌或外部）
  → ServiceClassLoader 传入 AutoConfiguredOpenTelemetrySdk.builder()
  → SPI 自动发现 controlplane 注册的 AutoConfigurationCustomizerProvider
  → controlplane 功能生效
```

### 运行时 Class Remapping

extension jar 使用原始（未 shaded）OpenTelemetry API 编译。agent 在运行时通过 `RemappingUrlStreamHandler` 自动完成 class remapping，因此 extension 无需关心 shading 问题。

### 依赖管理

controlplane 依赖的 gRPC、Protobuf 等传递依赖由 agent classloader 统一管理，不会与应用代码产生冲突。版本由项目根级 BOM 统一管控。

---

## 注意事项

1. **避免双重加载**：内嵌模式和独立模式二选一。如果 javaagent jar 已内嵌 controlplane，不要再通过 `-Dotel.javaagent.extensions` 重复加载同一个 extension
2. **base classifier jar**：使用 `opentelemetry-javaagent-<version>-base.jar`（`baseJavaagentJar`）的自定义发行版不包含 controlplane，需通过独立 extension 方式引入
3. **版本兼容性**：独立使用时，确保 controlplane-extension 的编译版本与目标 agent 版本兼容（主要是 OpenTelemetry SDK API 版本）

---

## 变更记录

### 模块化迁移（初始版本）

**变更内容**：将 controlplane 从 `javaagent-tooling` 硬编码依赖解耦到 `custom-extensions` 独立模块。

**修改文件**：

| 文件 | 操作 | 说明 |
|------|------|------|
| `custom-extensions/controlplane-extension/build.gradle.kts` | 新增 | 模块构建配置，使用 `otel.sdk-extension` 插件 |
| `javaagent-tooling/build.gradle.kts` | 修改 | 移除 `implementation("io.opentelemetry:opentelemetry-sdk-extension-controlplane")` |
| `settings.gradle.kts` | 修改 | 新增 `include(":custom-extensions:controlplane-extension")` |
| `javaagent/build.gradle.kts` | 修改 | 新增 `javaagentDependencies.add(javaagentLibs.name, project(":custom-extensions:controlplane-extension"))` |

**验证结果**：Gradle 依赖解析通过，编译通过，controlplane 正确包含在最终 javaagent jar 中。
