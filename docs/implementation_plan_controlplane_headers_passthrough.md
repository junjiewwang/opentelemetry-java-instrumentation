# 实施方案设计：控制面鉴权改为「解码后透传完整 headers」

## 需求与目标

把控制面鉴权从「从 headers 里抠 token、再拼一次 Bearer」改成「按 SDK 语义解码后透传完整 headers」：复用 `ConfigProperties.getMap()` 与 `URLDecoder.decode()`，保留 `otel.agent.control.token` 最高优先级，清理未使用的 `getHeaders()`。目标是只保留四个标准环境变量即可让控制面与数据面都正常工作，不再需要任何 `otel.agent.control.*` 参数。

本文是**方案设计**，用户尚未下达「开始实施」，因此未修改任何代码。所有结论均带本地源码文件与行号；运行时验证结果单独标注为「已确认事实 / 初步判断 / 建议动作」。文档不含任何 token、密钥或内网凭据。

## 运行时验证（otel-collector 技能，2026-11-24 复核）

工具链按技能 SOP 执行：`list_agents` → `agent_info` → `arthas_status` → `arthas_attach` → 多轮 `arthas_status` → 重试 attach → 用 `arthas_exec` / `arthas_sc` / `arthas_jad` / `vmtool` / `ognl` 完成核验。

已确认事实：在线 Agent 共 1 个，服务名 `springboot-jdk8u202-otel-demo`，状态 online，`process.pid=1`，启动时间 2026-11-23 19:39:04，注册时间 19:39:20，最后心跳 2026-11-24 10:17:09。`arthas_status` 持续 `not_attached`，但 `arthas_exec version` 成功且返回 `arthasState: RUNNING`、`tunnelReady: false`；`arthas_sc -d`、`arthas_jad`、`vmtool`、`ognl` 全部执行成功。说明 Arthas 进程已在目标 JVM 运行，这些工具走「Control Plane 任务链路 + 结构化命令桥」而非 Tunnel session；`not_attached` 只代表 Tunnel 未注册，不等于诊断能力不可用。

`sc -d` 确认 `ControlPlaneConfig` 由 `io.opentelemetry.javaagent.tooling.ExtensionClassLoader`（hash `7b02881e`）加载，未被 shading 重命名。`jad` 反编译结果与工作区源码逐行一致：含 `OTLP_HEADERS` 常量、`fromConfigProperties()` 用 `getString(OTLP_HEADERS)`、`extractTokenFromOtlpHeaders()` 只判断 `startsWith("bearer ")`、`getAuthorizationHeader()` 拼 `"Bearer "`、endpoint/protocol 的 dedicated > shared 优先级、未被使用的 `getHeaders()`；反编译行号映射也与源码一致（323 / 343 / 232 等）。**上一轮「运行时 jar 与工作区源码是否一致」的遗留问题已消除**。

脱敏运行时断言（只输出布尔/数字/来源，不输出凭据）：`OTEL_EXPORTER_OTLP_HEADERS` 存在且 `%20` 位于索引 20（对应 `authorization=Bearer` 的长度），证明确为 `Bearer%20<TOKEN>` 写法；对该值执行源码里的 `toLowerCase().startsWith("bearer ")` 返回 `false`；三个系统属性 `otel.agent.control.token` / `.endpoint` / `.protocol` 均存在；`vmtool --action getInstances` 读到运行时 `ControlPlaneConfig.getAuthTokenSource()` 为 `otel.agent.control.token (dedicated)`。

结论：根因链路在运行时被完整坐实——输入合规但运行时代码不解码且二次拼 Bearer，只靠标准 headers 会发出 `Bearer Bearer%20<TOKEN>`；当前进程正常是因为专属 token 以最高优先级覆盖了这条 fallback。按代码，三个 `-D` 中 endpoint/protocol 属重复配置，只有 token 是必需的。

初步判断：`tunnelReady: false` 更可能是该部署未启用或未连通 Tunnel Server，结构化命令桥本身工作正常；这属于诊断通道配置问题，与鉴权修复无关，未进一步追查。

建议动作：保留 Arthas 运行态（本轮未 `detach`，便于继续诊断）；实施后用同一组 `jad` / `vmtool` 命令复核 `ControlPlaneConfig` 已换成新逻辑、`getAuthTokenSource()` 变为 `otel.exporter.otlp.headers[Authorization]`，并在撤掉三个 `-D` 后确认控制面注册/心跳正常、401 消失。

## 现状改动面（逐文件证据）

| 文件 | 现状 | 目标改动 |
|---|---|---|
| `ControlPlaneConfig.java:28-40` | 定义 `otel.exporter.otlp.headers`、`otel.resource.attributes`、`otel.agent.control.token` 等键 | 保留键定义；`OTLP_HEADERS` 改为供 `getMap()` 使用 |
| `ControlPlaneConfig.java:114,121-123,140,145-152` | 保存原始 headers 字符串；构造时一次性解析 `authToken` / `authTokenSource`，日志只打来源 | 改为保存「解码后的完整 headers Map」；`authToken` 由最终 headers 派生，保证单一数据源；日志仍只打来源 |
| `ControlPlaneConfig.java:170-192,200-239` | `resolveAuthToken` 三级优先级 + `extractTokenFromOtlpHeaders()` 手工切分且不解码 | 删除 `extractTokenFromOtlpHeaders()`；改为解析器：`getMap()` → 逐值 `URLDecoder.decode` → 按来源生成最终 Authorization |
| `ControlPlaneConfig.java:321-347` | `getHeaders()` 返回原始字符串（全模块无调用者）、`getAuthToken()`、`getAuthorizationHeader()` 拼 `Bearer `、`hasAuthToken()`、`getAuthTokenSource()` | 删除 `getHeaders()`，新增语义明确的 `getResolvedHeaders(): Map<String,String>`；`getAuthorizationHeader()` 改为从最终 headers 派生；`getAuthToken()` / `hasAuthToken()` **必须保留**（见下方 Arthas 依赖） |
| `ControlPlaneConfig.java:463,476,517-523` | Builder 保存 `headers` / `resourceAttributes` / `dedicatedToken`，`fromConfigProperties()` 用 `getString(OTLP_HEADERS)` | Builder 改为保存 `Map<String,String> resolvedHeaders` 与各来源标记；`fromConfigProperties()` 用 `getMap(OTLP_HEADERS)` |
| `TransportConfig.java:18-32,52-60,98-129` | 只有一个 `@Nullable String authorizationHeader` 字段与 builder | 换成非 null 的 `Map<String,String> headers` 字段、`getHeaders()` 与 `headers(Map)` builder；`build()` 增加 key/value 非空校验 |
| `TransportFactory.java:35-44` | `.authorizationHeader(config.getAuthorizationHeader())` | `.headers(config.getResolvedHeaders())` |
| `HttpTransport.java:50,58-74,88-97` | 缓存单值，`if (authorizationHeader != null) requestBuilder.header("Authorization", ...)` | 缓存 Map，遍历 `requestBuilder.header(k, v)`；固定协议头在用户 headers 之后设置，避免被覆盖 |
| `GrpcTransport.java:76,80-84,129-142` | 同上，另有 `HEADER_TE`、`grpc-encoding` 等固定头 | 同上，且保留 gRPC 必需头优先级 |
| `DefaultControlPlaneService.java:56-65` | 初始化日志用 `config.getAuthorizationHeader() != null` 判断 hasAuth | 改用 `!config.getResolvedHeaders().isEmpty()`，不打印任何 header 值 |
| `ControlPlaneService.java:134-136` | `TransportFactory.create(config)` → `DefaultControlPlaneService` | 无需改，链路唯一，改动可收敛在 config + transport 层 |

两个必须保留的旁路依赖，是本方案最容易被漏掉的地方：`ControlPlaneManager.java:622-627` 把 `config.getAuthToken()` 继承给 `ArthasConfig` 用于 Tunnel 认证，`ArthasBootstrap.java:696-701` 又把 `config.getAuthToken()` 写进 `arthas.appName`。因此 `getAuthToken()` 不能删，且必须始终返回**不含 Bearer 前缀的纯 token**；否则即使 HTTP 头修好了，Arthas 侧也会带上错误值。后者把 token 当作应用名使用，还存在凭据扩散到 Tunnel Server 与日志的风险，属于独立缺陷，建议单独立项修复，不并入本次改动面。

对照实现与依赖边界：SDK 的 `OtlpConfigUtil.configureOtlpHeaders()` 是包级 `static` 方法（`exporters/otlp/all/.../OtlpConfigUtil.java:148-165`），类注释明确「not for public use, APIs unstable」（同文件 `:28-32`），且 controlplane 的 `build.gradle.kts:12-26` 未依赖 `exporters/otlp/all`。所以方案 2 不直接复用该类，而是用 SPI 自带的 `ConfigProperties.getMap()`（`DefaultConfigProperties.java:221-244` 有 `@Override`，属接口方法，无需新增依赖）配 `java.net.URLDecoder`，异常沿用 `autoconfigure-spi` 的 `ConfigurationException`（controlplane 已 `api` 依赖该模块，`build.gradle.kts:15`）。把解析逻辑上移到公共层、让 SDK 与控制面共用，是上一轮报告的方案 3，跨仓库跨模块，本次不做。

## 目标设计

单一数据源。启动时由 `fromConfigProperties()` 一次性算出 `resolvedHeaders`（不可变 Map，key 大小写不敏感去重），所有下游（HTTP/gRPC 请求头、日志判断、token 派生）都只读它，不再各自解析原始字符串。优先级保持现有三级语义：若 `otel.agent.control.token` 非空，则以 `Bearer <token>` 覆盖 Map 中的 Authorization；否则若 `otel.resource.attributes` 含 `token`，同样覆盖；否则直接透传 `OTEL_EXPORTER_OTLP_HEADERS` 解码后的全部键值。`authToken` 与 `authTokenSource` 从最终 Map 的 Authorization 派生（剥掉 `Bearer ` 得到纯 token），避免两处独立计算再次漂移，也满足 DRY 与高内聚。

解析规则与 SDK 对齐：用 `config.getMap("otel.exporter.otlp.headers")` 完成 `key=value` 逗号分隔解析（含空值过滤、重复键取后者），再对每个 value 做 `URLDecoder.decode(value, StandardCharsets.UTF_8.displayName())`，非法编码抛 `ConfigurationException` 并带上 key（**不带 value**，避免把凭据写进异常与日志）。因此 `Bearer%20<TOKEN>` 会得到 `Bearer <TOKEN>`，`%2C` 得到逗号，`%25` 得到百分号；`+` 会被解成空格，这与 SDK 行为一致但与 W3C Baggage「字面加号应写 `%2B`」有细微差异，需要在配置文档里写明。

边界约定：控制面不是 OTLP 信号，本次只消费通用 `otel.exporter.otlp.headers`，明确不消费 `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_HEADERS`（SDK 侧的优先级见 `OtlpConfigUtil.java:150-153`），这条约定要写进 README 配置表。用户自定义 headers 不得覆盖协议头（`te`、`grpc-encoding`、OkHttp 自动生成的 `Content-Type` 等），实现上先应用用户 headers、后设置固定头，并对保留头做黑名单过滤；Map 内 `authorization` 与 `Authorization` 并存时按大小写不敏感归一，避免 OkHttp 重复设置。`TransportConfig` 持有 Map 的不可变副本，防止运行期被修改。

兼容策略：`getAuthorizationHeader()`、`hasAuthToken()`、`getAuthTokenSource()` 在 Sprint 1/2 保留为派生方法，Sprint 3 再评估是否删除；`TransportConfig.authorizationHeader()` builder 可保留为兼容入口（内部转成单键 Map）直至确认没有外部调用者。该模块是自定义发布的 artifact，公开 API 删除仍属破坏性变更，需按 VERSIONING 约定处理。

## 变更清单

新增：一个内部解析器（建议 `config/OtlpHeadersResolver`，职责仅为 `ConfigProperties → Map<String,String>` 解码与归一，便于单测与将来上移公共层）；`ControlPlaneConfigTest` 的解码/优先级用例；一个端到端 header 断言测试（现有 `src/test` 无 transport 测试，可用已有测试依赖 `armeria-junit5`，见 `build.gradle.kts:56-58`，起假 server 捕获 `/v1/control/*` 请求的 Authorization 值）。

修改：`ControlPlaneConfig`、`TransportConfig`、`TransportFactory`、`HttpTransport`、`GrpcTransport`、`DefaultControlPlaneService`，以及 `ControlPlaneConfigTest` 中现有 4 个 token 优先级用例（`:186-251`，它们的断言依赖 `getAuthToken()` / `getAuthTokenSource()` 字符串，需同步更新，特别是把 `Authorization=Bearer header-token` 改成 `%20` 合规写法）。

删除：`extractTokenFromOtlpHeaders()`、`getHeaders()` 原始字符串字段与 getter、`TransportConfig.authorizationHeader` 单值字段（Sprint 3 完成）。

文档：`sdk-extensions/controlplane/README.md:29-39` 配置表补充 headers 透传与编码说明、`otel.agent.control.token` 覆盖语义、信号级变量不消费的边界；更新 `docs/2026-05-21/protocol-default-and-logging.md` 的优先级表（`:30-41`）中 Token 一行的描述。

发布链：修改 SDK 源码后必须重新 `publishToMavenLocal` 覆盖 `opentelemetry-sdk-extension-controlplane:1.57.0`（或升版本并同步 instrumentation 的 `dependencyManagement/build.gradle.kts:8`），再重建 javaagent；否则改动不会进入运行时。

## 分 Sprint 与验收标准

| Sprint | 内容 | 功能验收 | 回归 / KPI | 回滚 |
|---|---|---|---|---|
| 1 观测先行 | 新增 `OtlpHeadersResolver` 与单测；`ControlPlaneConfig` 增加 feature flag `otel.agent.control.headers.passthrough.enabled`（**默认 false**，关闭时完全走旧逻辑）；启动日志打印解析出的 header **key 列表**与 token 来源，不打印值 | 新解析器单测覆盖 `%20`、多 header、`%2C`/`%25`、`+`、非法编码、空值、大小写去重 | 全量现有测试通过；运行时行为零变化；日志不出现任何凭据 | 只需删除新代码，flag 默认关闭天然可回滚 |
| 2 灰度切换 | flag 打开时走新链路；`TransportConfig` / `TransportFactory` / 两个 Transport / `DefaultControlPlaneService` 适配 Map；保留兼容派生方法 | 端到端断言实际发出的 Authorization 为 `Bearer <TOKEN>` 且无重复前缀；多 header 生效；专属 token 覆盖 headers 生效 | 控制面 401 消失、心跳/任务下发正常；Arthas token 继承正确；数据面 OTLP 上报不受影响；`ControlPlaneConfigTest` 全部通过 | flag 置回 false 即回到旧路径 |
| 3 收敛清理 | 默认打开并移除 flag；删除 `extractTokenFromOtlpHeaders()`、原始 `getHeaders()`、旧单值字段；更新 README 与设计文档；重新发布 artifact 并重建 javaagent | 只配四个标准环境变量启动，控制面注册/心跳成功，Arthas 任务可下发 | 全量单测 + 端到端测试；无旧逻辑残留（静态搜索确认）；无敏感信息日志 | 保留 Sprint 2 的 tag 可整体回退 |

每步都按「Planner 出清单 → Coder 实现 → Verifier 独立跑验收」协作；每步输出变更清单、风险、验证证据与下一步。feature flag 默认关闭，先观测后决策。

## 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| `getAuthToken()` 被 Arthas 链路依赖（`ControlPlaneManager.java:622-627`、`ArthasBootstrap.java:698`） | 误删或语义改成「完整 header 值」会导致 Tunnel 认证/appName 异常 | 保留该方法并断言其始终为纯 token；Sprint 2 端到端覆盖 Arthas 继承 |
| `ArthasBootstrap.java:698` 把 token 当 `arthas.appName` | 凭据可能扩散到 Tunnel Server 与日志 | 本次不改，单列缺陷单；日志与异常输出一律不带 header 值 |
| 公开 API 变更（`getHeaders()`、`authorizationHeader()` 删除/改签名） | 外部编译失败 | Sprint 1/2 保留兼容派生方法，Sprint 3 前确认调用方；按版本约定处理 |
| 用户 headers 覆盖协议必需头 / 同名大小写重复 | gRPC 或 HTTP 请求异常 | 归一去重 + 固定头后设置 + 保留头黑名单；端到端测试覆盖 |
| `URLDecoder` 把 `+` 解成空格 | 与 W3C Baggage 严格语义有差异 | 与 SDK 行为保持一致，写入文档；必要时提供 `%2B` 示例 |
| 非法百分号编码导致启动失败 | Agent 启动被阻断 | 抛带 key 的 `ConfigurationException`，错误信息不含值；文档给出编码示例 |
| 版本发布链断裂（工作区源码 ≠ 运行时 1.57.0 jar） | 改了不生效 | 实施前先用 Arthas `jad` 或 sources.jar 比对；实施后重新 publish 并重建 javaagent，用启动日志 `source=` 复核 |
| 信号级 headers 变量未消费 | 用户按 OTLP 习惯配置后不生效 | README 明确边界；如需支持，另立需求 |

## 验证证据清单

源码证据：见上表各文件行号，以及 `OtlpConfigUtil.java:148-165`（SDK 解码语义）、`DefaultConfigProperties.java:51-85,221-244`（环境变量合并与 getMap）、`ConfigUtil.java:66-76`（键归一化）、`OtlpConfigUtil.java:150-153`（信号级覆写）、`build.gradle.kts:15,56-58`（依赖边界与测试依赖）。

运行时证据（2026-11-24 复核）：`list_agents` / `agent_info` 的在线状态与心跳时间；`arthas_exec version` 返回 `arthasState: RUNNING`、`tunnelReady: false`；`sc -d` 的类加载器信息；`jad` 反编译结果与工作区源码一致；`ognl` 的 `%20` 索引与 `startsWith("bearer ")=false`；`vmtool getInstances` 的 `getAuthTokenSource()=otel.agent.control.token (dedicated)`。运行时代码版本、输入值、缺陷命中条件与当前绕过方式均已直接证实。

实施后必须补齐的证据：启动日志中 endpoint/protocol 的 `source=otel.exporter.otlp.* (shared)`、`Auth token configured from source: otel.exporter.otlp.headers[Authorization]`；端到端测试捕获到的真实 Authorization 值（断言不含重复 `Bearer` 且不含 `%20`）；只配四个标准环境变量时控制面注册成功、401 消失、Arthas 任务可下发；用同一组 `jad` / `vmtool` 命令确认运行时类已换为新逻辑、token 来源变为 headers。

## 实施记录（2026-11-24，已按「开始实施」执行）

### 变更清单

新增 `config/OtlpHeadersResolver.java`：单一职责解析 `ConfigProperties.getMap("otel.exporter.otlp.headers")` → 逐个 `URLDecoder.decode` → 大小写不敏感去重 → 不可变 Map；非法编码抛 `ConfigurationException` 且异常信息只带 header 名、不带值；另提供 `find` / `putIgnoreCase` / `bearerToken` 供配置层复用。新增 `client/transport/RequestHeaders.java`：HTTP 与 gRPC 共用的请求头应用逻辑，过滤协议保留头，避免两处重复实现。

修改 `ControlPlaneConfig.java`：以 `resolvedHeaders` 为单一数据源；`fromConfigProperties()` 完成解码与三级优先级合并（专属 token > resource token > headers），构造器从最终 headers 派生 `authToken` / `authTokenSource` / `getAuthorizationHeader()`；删除 `extractTokenFromOtlpHeaders()` 与未使用的 `getHeaders()`；保留 `getAuthToken()` / `hasAuthToken()`（`ControlPlaneManager` 与 `ArthasBootstrap` 依赖，且保证返回纯 token）；新增只输出 header 名称的 debug 日志。修改 `TransportConfig.java`（单值 `authorizationHeader` → 不可变 `Map` + 校验）、`TransportFactory.java`、`HttpTransport.java`、`GrpcTransport.java`、`DefaultControlPlaneService.java`，全部改为一次性应用完整 headers，固定协议头在用户 headers 之后设置。

测试：改写 `ControlPlaneConfigTest` 的 headers fallback 用例为 `%20` 输入，新增解码、多 header 与 `%2C`/`%25`、专属 token 覆盖、resource token 覆盖、大小写重复键归一、非法编码快速失败、空配置共 7 个用例；新增 `RequestHeadersTest`（多 header 透传与保留头保护）与 `HttpTransportHeaderTest`（Armeria 假服务端捕获并断言真实发出的 `Authorization` 与自定义头）。文档更新 `README.md` 配置表与 `docs/2026-05-21/protocol-default-and-logging.md` 优先级表。

### 对原计划的调整（需知晓）

原 Sprint 1 计划「feature flag 默认关闭、先观测后决策」。由于观测阶段已通过 Arthas 运行时复核完成（反编译一致、`%20` 输入与命中条件均已证实），且默认关闭会让本次 bug 修复不生效，实施时**不引入双逻辑开关**：直接删除旧解析路径以清除重复代码（DRY），回滚依靠 git 回退与 `mavenLocal` 旧 artifact 切回，而非运行时 flag。可观测性通过「token 来源日志 + debug 打印 header 名称」保留，日志不输出任何 header 值。

### 验证证据

静态检查：改动文件 IDE 诊断 0 条；全模块搜索确认已无 `extractTokenFromOtlpHeaders` / 单值 `authorizationHeader` / 旧 `getHeaders()` 残留。构建与测试：Gradle `:sdk-extensions:controlplane:test`（三个测试类）结果见本节末尾「构建验证结论」回填。端到端断言覆盖真实 HTTP 请求头，而非仅断言配置对象返回值。

### 风险与缓解

公开 API 变更（`getHeaders()` 删除、`TransportConfig.authorizationHeader()` 删除）已确认模块内无其它调用者；Arthas 链路依赖的 `getAuthToken()` 保持纯 token 语义并在单测中断言；用户 headers 不能覆盖 `accept-encoding` / `content-type` / `te` / `grpc-encoding` 等协议头；异常与日志均不含凭据；`+` 被 `URLDecoder` 解为空格与 SDK 行为一致，已在 README 说明编码规范。

## 未完成任务

- 构建验证结论回填 —— 已完成，定向测试 25 个用例与全模块 23 个测试报告均 0 失败，见「构建验证结论」。
- 重新 `publishToMavenLocal` 覆盖 `opentelemetry-sdk-extension-controlplane:1.57.0`（或升版本并同步 instrumentation 的 `dependencyManagement/build.gradle.kts`），再重建 javaagent——**这一步未完成前，源码改动不会进入线上运行的那个 jar**。
- 撤掉三个 `otel.agent.control.*` 参数，只保留四个标准环境变量做运行时复核：控制面注册/心跳正常、401 消失、`getAuthTokenSource()` 变为 `otel.exporter.otlp.headers[Authorization]`。

## 遗留问题

`arthas_status` 一直 `not_attached`（`tunnelReady: false`）的原因未追查，但已证实不影响结构化命令桥的诊断能力；`ArthasBootstrap` 把 token 当 `arthas.appName` 的独立缺陷未立项（存在凭据扩散风险）；信号级 headers 变量是否要支持未定；方案 3（公共解析层）未做详细设计；版本一致性问题已消除。

## 下一步

完成构建验证并回填结论，随后重新发布 artifact、重建 javaagent，用保留的 Arthas 运行态做实施前后对照验证。

## 构建验证结论（2026-11-24，已完成）

- 定向测试：`:sdk-extensions:controlplane:test` 指定 `ControlPlaneConfigTest`、`RequestHeadersTest`、`HttpTransportHeaderTest`，`BUILD SUCCESSFUL`；测试报告分别为 21 / 2 / 2 个用例，`failures=0`、`errors=0`。其中 `HttpTransportHeaderTest` 通过 Armeria 假服务端断言真实发出的 `Authorization` 与自定义头，不是只断言配置对象。
- 全量回归：不带 `--tests` 再跑一次 `:sdk-extensions:controlplane:test`，`BUILD SUCCESSFUL in 11s`；`build/test-results/test` 下共 23 个测试报告，检索 `failures="[1-9]` / `errors="[1-9]` 命中 0 条，即全模块 0 失败。
- 编译产物确认：`build/classes/java/main/.../config/OtlpHeadersResolver.class` 已生成，`compileTestJava` 通过，说明运行的是新代码而非旧 class；改动文件 IDE 诊断 0 条。
- 变更文件（SDK 仓库）：修改 9 个（`ControlPlaneConfig`、`TransportConfig`、`TransportFactory`、`HttpTransport`、`GrpcTransport`、`DefaultControlPlaneService`、`ControlPlaneConfigTest`、`README.md`、`docs/2026-05-21/protocol-default-and-logging.md`），新增 4 个（`OtlpHeadersResolver`、`RequestHeaders`、`RequestHeadersTest`、`HttpTransportHeaderTest`）。instrumentation 仓库新增/更新 3 份分析文档。
- 注意：以上验证发生在 SDK 仓库源码与本地构建产物层面，**线上运行的 javaagent jar 仍是旧的 1.57.0 artifact**；必须重新发布并重建 javaagent 才能生效（见「未完成任务」）。
