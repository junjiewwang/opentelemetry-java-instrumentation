# 修复记录：Gradle Plugin Portal 依赖解析 403

## 需求

在 `opentelemetry-java-instrumentation` 仓库构建 `bom` 时失败：`bom/build.gradle.kts:1` 应用 `otel.bom-conventions` 抛异常，配置 `:smoke-tests-otel-starter:spring-boot-4` 时 `configuration 'classpath'` 无法解析，多个 Spring Boot 4.0.0 / Spring Framework 7 / Jackson 3 / JNA 依赖从 `plugins.gradle.org/m2` 重定向到 `repo.maven.apache.org` 后返回 HTTP 403。当前本机已存在全局 init script `~/.gradle/init.d/maven-central-mirror.gradle`（本地开发机专用，作者注释自述 Maven Central 在该网络出口返回 403，故把 Maven Central 仓库 URL 重写到镜像）。

本次目标：扩展该 init script 的作用域，让 plugin classpath 也走可达镜像，使构建恢复；改动需最小、可回滚、不影响其它仓库解析。

## 根因（已取证）

- `~/.gradle/init.d/maven-central-mirror.gradle`（改动前版本）的 `mirrorUrl` / `centralHosts` / `rewriteCentralRepo`：只重写 host 属于 `repo.maven.apache.org` / `repo1.maven.org` 的 `MavenArtifactRepository` 对象 URL。注意该脚本在两次排查之间已被调整：主镜像从腾讯 `maven-public` 换成了 `https://maven.aliyun.com/repository/public/`，注释说明腾讯镜像会拦截被标记漏洞的历史组件。
- `guard` 覆盖 `beforeSettings.pluginManagement.repositories`、`settingsEvaluated.dependencyResolutionManagement.repositories`、`allprojects.buildscript/repositories`；原 `beforeSettings` 只注册 guard，不新增仓库。
- `settings.gradle.kts:1-14`：`pluginManagement` 只有 `plugins {}`，无 `repositories {}`，Gradle 使用默认 `gradlePluginPortal()`（host `plugins.gradle.org`），不在 `centralHosts`，不会被重写。
- 错误信息给出重定向证据：`Could not get resource https://plugins.gradle.org/m2/...` → `Could not GET https://repo.maven.apache.org/maven2/... 403`。HTTP 层 302 的目标不受仓库对象 URL 重写影响。
- 触发链：`conventions/src/main/kotlin/otel.bom-conventions.gradle.kts:12-16` 对所有非 bom 子项目 `evaluationDependsOn` → `settings.gradle.kts:159` 包含 spring-boot-4 → `smoke-tests-otel-starter/spring-boot-4/build.gradle.kts:1-5` 使用 `springBoot40` catalog（`settings.gradle.kts:47` = 4.0.0）→ 解析插件 classpath。
- `classpath` 属 buildscript/plugin 解析，不走 `settings.gradle.kts:28-32` 的 `dependencyResolutionManagement.repositories`。
- 缓存中已有 `spring-boot-gradle-plugin-4.0.0.jar`，但缺 `spring-boot-buildpack-platform`、`spring-boot-loader-tools`、`spring-core:7.0.1`、`jackson 3.0.2`、`jna 5.17.0`，故 `--offline` 不可行。
- SDK 仓库 `settings.gradle.kts:1-11` 同样无 `pluginManagement.repositories`，但没有 spring-boot-4 子项目且插件依赖已缓存，因此此前构建成功，不是配置差异。

## 实施方案（最小 diff）

在 `beforeSettings` 中显式配置 `settings.pluginManagement.repositories`：把脚本已声明的阿里云 public 镜像注册为**首选**仓库（提供插件的普通传递依赖），并显式保留 `gradlePluginPortal()` 作为 plugin marker 来源；随后继续对这组仓库应用原有 guard（保证后续新增的 `mavenCentral()` 仍被重写）。同时给该块加异常保护，遵循脚本「broken init script must never take down the whole build」的既有原则；若配置仓库时抛异常，catch 分支退回到原来的纯 guard 行为。不引入新的镜像域名、不改动 `settingsEvaluated` 与 `allprojects` 分支。同步修正文件头注释，去掉「Gradle Plugin Portal 保持不动」的旧描述。

## 实施进展

- [x] 需求与根因取证完成
- [x] 记录文档落盘
- [x] init script 修改（文件头注释 + `beforeSettings` 块）
- [x] 真实构建验证（`:bom:help`）
- [x] 验证结论回填与文档更新

## 未完成任务

- 完整业务任务（`:custom-extensions:controlplane-extension:build` / 重建 javaagent）未重新执行；本次只验证到配置阶段与原失败路径消失。
- 上一轮控制面修复的发布链需确认：daemon 日志显示 11:50 有一次 SDK 仓库 `publishToMavenLocal` 成功（`BUILD SUCCESSFUL in 1m 24s`），但那发生在本 init script 改动之前且非本轮执行；11:52 / 11:53 / 11:55 instrumentation 仓库的 `controlplane-extension:build` / `clean` 记录分别为取消或 403 失败，需在本次配置修复后重跑并确认 artifact 版本。

## 遗留问题

- 镜像对 Spring Boot 4.0.0 依赖的覆盖已由本次构建成功间接证实，但未用独立 HTTP 请求逐个核验（本机 `curl` 两次超时）。
- 保留的 `gradlePluginPortal()` 在镜像未命中时仍可能 302 到 Maven Central 并 403，属于兜底路径的已知风险；本次未触发。
- `plugins.gradle.org` 未加入重写集合，是有意保留 plugin marker 解析能力；若后续其它插件再报 403，需评估改用专门的 gradle-plugin 镜像（需先验证可达性）。
- 该 init script 是全局配置，影响本机所有 Gradle 构建；改动后需留意包含构建（composite builds）行为。
- 命令执行工具多次 300s 超时的原因已查明：`:bom:help` 实际运行 5m57s，期间占用 shell 与 daemon，并非环境故障。

## 回滚方式

改动仅涉及 `beforeSettings` 块。改动前原文：

```groovy
beforeSettings { settings ->
    guard(settings.pluginManagement.repositories)
}
```

回滚时把新块替换回上述三行即可；若需完全移除该缓解脚本，按文件头注释删除 `~/.gradle/init.d/maven-central-mirror.gradle`。

## 验证证据

- 失败基线：`~/.gradle/daemon/9.2.1/daemon-56759.out.log` 第 3904-3945 行，11:55:30 的 instrumentation 仓库构建仍报 `bom/build.gradle.kts:1` → `:smoke-tests-otel-starter:spring-boot-4` classpath → `repo.maven.apache.org` 403，`BUILD FAILED in 4s / 18 actionable tasks: 18 up-to-date`，与用户贴出的报错完全一致。
- 修复后：同一 daemon 日志第 8497 行 `tasks: :bom:help`，第 8523 行 `BUILD SUCCESSFUL in 5m 57s`（13:25:18–13:31:17，`currentDir=.../opentelemetry-java-instrumentation`），`27 actionable tasks: 1 executed, 26 up-to-date`，配置缓存已存储；该段无 403，也无 `[maven-mirror] pluginManagement setup skipped` 兜底 WARN，说明新增仓库配置未抛异常。
- 依赖缓存：改动前 `spring-boot-buildpack-platform-4.0.0.jar`、`spring-boot-loader-tools-4.0.0.jar`、`spring-core-7.0.1.jar`、`jackson-core-3.0.2.jar`、`jna-5.17.0.jar` 均未缓存，改动后全部出现在 `~/.gradle/caches/modules-2/files-2.1/`，证明 plugin classpath 已从可达镜像完成下载。
- 对照：SDK 仓库 `settings.gradle.kts:1-11` 同样无 `pluginManagement.repositories`，但没有 spring-boot-4 子项目，此前构建成功，排除「两边配置差异」这一解释。

结论：根因假设（Plugin Portal 重定向未被 init script 覆盖）得到验证，修复有效；原失败路径的 403 已消失。
