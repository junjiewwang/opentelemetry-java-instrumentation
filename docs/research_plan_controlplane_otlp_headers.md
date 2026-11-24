# 研究计划：controlplane-extension 上报 401 与 OTEL_EXPORTER_OTLP_HEADERS 适配性分析

## 需求记录

用户反馈：Java Agent 通过 `custom-extensions/controlplane-extension` 上报控制面数据时返回 401。已配置的标准 OTEL 环境变量（token 已脱敏，用 `<TOKEN>` 表示）：

- `OTEL_EXPORTER_OTLP_HEADERS=authorization=Bearer%20<TOKEN>`
- `OTEL_EXPORTER_OTLP_ENDPOINT=<COLLECTOR_ENDPOINT>`
- `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`
- `OTEL_SERVICE_NAME=springboot-jdk8u202-otel-demo`

必须额外显式配置以下 JVM 参数才能上报成功（token 已脱敏）：

- `-Dotel.agent.control.endpoint=<COLLECTOR_ENDPOINT>`
- `-Dotel.agent.control.token=<TOKEN>`
- `-Dotel.agent.control.protocol=http/protobuf`

需要回答两个问题：

1. 为什么标准 `OTEL_EXPORTER_OTLP_*` 环境变量对 extension 上报不生效，导致 401？根因链路是什么？
2. 能否让 extension 适配（复用/桥接）`OTEL_EXPORTER_OTLP_HEADERS` 等标准配置？有哪几套方案，代价与风险如何？

约束：本轮是**分析与方案讨论**，用户未下达"开始实施"，因此**不修改任何代码**；文档不含敏感信息（token、内部域名按脱敏处理原则引用）。

## 查询类型判定

属于 **Depth-first（深度优先）** 查询：核心问题单一（配置为何不生效 + 能否适配），但需要从多条独立证据链交叉验证：

- A. instrumentation 仓库：`controlplane-extension` 的配置读取、HTTP 客户端构造、Authorization 头拼接、401 产生位置。
- B. opentelemetry-java SDK：标准 `OTEL_EXPORTER_OTLP_*` 环境变量的解析链路（autoconfigure → exporter builder → headers/endpoint/protocol），特别是 `%20` 编码与 `Bearer` 值的处理方式。
- C. 两条链路之间是否存在既有桥接/SPI/复用点（ConfigProperties、DeclarativeConfig、extension SPI），决定适配方案的可行性与改动面。
- D. 规范层佐证：OTEL 规范对 `OTEL_EXPORTER_OTLP_HEADERS` 的编码要求（如 `%20` 是否由 SDK 解码）。

最终产物：一份带文件路径与行号证据的分析报告 + 适配方案对比 + 遗留问题清单。

## 信息检索策略

一手证据优先：两个本地仓库的源码（instrumentation 与 opentelemetry-java SDK），这是本问题唯一可靠的判定依据。所有断言必须能追溯到具体文件与行号；没有证据的部分明确写"不知道"。

关于微信公众号文章检索：计划中要求纳入 `wechat-article-search` 技能，但已核对当前会话可用技能清单，**该技能在本环境未提供**，无法加载执行。替代方案：由子代理用 `web_search` 检索中文技术文章（关键词 `OTEL_EXPORTER_OTLP_HEADERS`、`OTLP HTTP exporter`、`opentelemetry java headers 编码`、`控制面 extension 上报 401`，时间范围 2023-01 至 2026-11），仅作为**旁证**，用于交叉验证社区对 headers 格式/编码的共识；同时用 `web_fetch` 取 OpenTelemetry 官方规范页与 opentelemetry-java 官方 docs。综合方式：本地源码结论为主、中文文章与官方规范为辅；若二者冲突，以本地源码与官方规范为准，并在报告中标注差异。

## 子代理分工（并行执行）

- 子代理 A：instrumentation 仓库中 `controlplane-extension` 全链路定位，输出配置文件、客户端类、header 拼接、401 相关代码的文件绝对路径 + 行号 + 关键片段；只读，不改代码。
- 子代理 B：`/Users/junjiewwang/IdeaProjects/github/opentelemetry-java` 中标准 OTLP 配置解析链路，输出环境变量名常量、解码逻辑、headers 传递路径的证据；只读。
- 子代理 C：两仓库间的复用/桥接机制调查 + 官方规范与中文文章旁证检索；只读。

主代理负责：校验子代理给出的证据（自行复读关键文件）、交叉比对、方案设计（不实施）、撰写与保存报告。

## 预期输出

`docs/research_report_controlplane_otlp_headers.md`：Executive Summary、根因链路（带证据）、SDK 标准链路（带证据）、适配方案对比与推荐、结论、局限性、References（带 URL）；并在本计划文件中回填实施进展、未完成任务与遗留问题。

## 实施进展

- [x] 需求与约束确认（不实施代码、文档脱敏）
- [x] 研究计划落盘
- [x] 子代理证据收集（A 控制面链路 / B SDK 标准链路 / C 规范与社区旁证，三路并行）
- [x] 主代理复读关键文件校验证据（两个仓库共约 15 个源码、测试、文档、构建文件）
- [x] 方案对比与报告撰写
- [x] 报告落盘：`docs/research_report_controlplane_otlp_headers.md`
- [x] 回填本文件（进展、未完成任务、遗留问题）

说明：本轮为分析任务，用户未下达「开始实施」，因此**未修改任何代码**，仅新增/更新 docs 下两份文档。

## 已确认结论摘要

- 环境变量能到达控制面：`DefaultConfigProperties` 合并 `System.getenv()` 并做键归一化，SPI 拿到完整 `ConfigProperties`。
- endpoint / protocol 已被复用（`otel.agent.control.*` > `otel.exporter.otlp.*` > 兜底），按当前源码两个对应 `-D` 冗余。
- 401 直接原因：`extractTokenFromOtlpHeaders()` 不做百分号解码且二次拼 `Bearer `，`Bearer%20<TOKEN>` 变成 `Bearer Bearer%20<TOKEN>`；SDK 数据面用 `URLDecoder.decode` 后原样透传，语义不一致。
- 推荐方案：改为「解码后透传完整 headers」（方案 2），保留三级优先级，补单测与端到端断言。

## 未完成任务（需「开始实施」后推进）

- 方案 2 的代码实现：`ControlPlaneConfig`、`TransportConfig`、`TransportFactory`、`HttpTransport`、`GrpcTransport`。
- 清理未使用的 `getHeaders()` 与 `extractTokenFromOtlpHeaders()`。
- 补充测试：`%20`、无 Bearer 前缀、多 header、`%2C` / `%25`、空值、非法编码、专属 token 优先级、端到端发出的 `Authorization` 断言。
- 重新发布 `opentelemetry-sdk-extension-controlplane` 到 `mavenLocal`（或升版本并同步 `dependencyManagement/build.gradle.kts`），再重建 javaagent 验证。

## 遗留问题

- **运行时 jar 与工作区源码是否一致尚未证实**：本地 `~/.m2` 同时存在 `1.57.0` 与 `1.58.0-SNAPSHOT`，三次解压/查看 jar 内容的命令均超时被取消。若线上是更早的 `1.57.0` 构建且不含复用逻辑，则 endpoint/protocol 的两个 `-D` 也是必需的。验证方式见报告「验证路径与现场自证」：看启动日志 `[ENDPOINT_RESOLVED] source=` / `[PROTOCOL_RESOLVED] source=` 与 `Auth token configured from source:`，或解包 sources.jar 比对。
- 未实跑复现 401、未抓包确认实际发出的 `Authorization` 值，结论为静态代码推导。
- 无 collector 服务端代码，不能排除服务端对 `/v1/control` 另有鉴权策略；客户端侧错误已足以解释 401。
- 控制面是否应参与 `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_HEADERS` 信号级覆写，尚未定义，需在方案实施时明确并写入文档。
- 方案 3（把 headers 解析抽到 SDK 公共层）跨仓库、跨模块，涉及 `internal` 包可见性与新增依赖，未做详细设计，建议单独立项。
- 计划要求的 `wechat-article-search` 技能在当前环境未提供，中文社区旁证改由网络检索替代；官方规范与本地源码为一手依据。

## 第二轮补充（2026-11-24）：运行时验证与实施设计

- [x] 按 `mcpext-arthas-diagnostic` 技能 SOP 连接 otel-collector：`list_agents` → `agent_info` → `arthas_status` → `arthas_attach`（重试一次）→ 多轮轮询 → `arthas_detach` 清理。
- [x] 运行时事实：1 个在线应用 `springboot-jdk8u202-otel-demo`，状态 online，启动/注册/心跳时间已记录（见实施设计文档，已脱敏）；两次 attach 均 `accepted=true / pending=true`，但 `arthas_status` 持续 `not_attached`；detach 返回已停止。
- [x] 初步判断（非结论）：控制面注册/心跳与任务下发链路可用，attach 卡在目标 JVM 的 Arthas 启动或 Tunnel 建连，而非控制面 401；未取得 `arthas_jad` / `ognl` 证据，版本一致性遗留问题仍存在。
- [x] 产出实施设计文档 `docs/implementation_plan_controlplane_headers_passthrough.md`：方案 2 的逐文件改动面、单一数据源设计、变更清单、3 个 Sprint 与验收标准、feature flag 默认关闭与回滚、风险与缓解、验证证据清单。
- [x] 关键新发现：`getAuthToken()` 被 `ControlPlaneManager.java:622-627`（Arthas token 继承）与 `ArthasBootstrap.java:698`（写入 `arthas.appName`）依赖，不能删除且必须返回不含 Bearer 前缀的纯 token；后者把 token 当应用名使用属独立缺陷，并有凭据扩散风险。
- [ ] 仍未修改任何代码，等待「开始实施」指令；建议实施前先重试 attach 完成 `jad` 比对，坐实运行时版本一致性。

## 第三轮补充（2026-11-24）：重新 attach 后的运行时复核

- [x] 重新 `arthas_attach` 后确认：`arthas_exec version` 成功、`arthasState: RUNNING`、`tunnelReady: false`；`arthas_status` 仍 `not_attached`，但 `arthas_sc` / `arthas_jad` / `vmtool` / `ognl` 全部可用（走结构化命令桥，不依赖 Tunnel 注册）。
- [x] `jad` 反编译 `ControlPlaneConfig` 与工作区源码逐行一致（含 `extractTokenFromOtlpHeaders`、二次拼 Bearer、endpoint/protocol 复用），**版本一致性遗留问题已消除**。
- [x] 脱敏运行时断言：`OTEL_EXPORTER_OTLP_HEADERS` 中 `%20` 位于索引 20；`startsWith("bearer ")` 为 false；三个 `otel.agent.control.*` 系统属性均存在；`vmtool getInstances` 得到 `getAuthTokenSource()=otel.agent.control.token (dedicated)`。
- [x] 根因链路在运行时坐实；报告 Limitations、实施设计验证章节与本文件已同步更新。
- [x] Arthas 运行态保留（未 detach），便于实施前后用同一组命令做对照验证。
- [x] 第三轮完成运行时根因坐实；`tunnelReady: false` 的通道配置问题未追查（不影响诊断能力）。

## 第四轮补充（2026-11-24）：开始实施修复

- [x] 用户下达「开始实施修复」，已按 `docs/implementation_plan_controlplane_headers_passthrough.md` 完成方案 2 的源码改造与测试补充，未引入默认关闭的 feature flag（原因见实施记录「对原计划的调整」）。
- [x] 新增 `OtlpHeadersResolver`、`RequestHeaders`、`RequestHeadersTest`、`HttpTransportHeaderTest`；改造 `ControlPlaneConfig`、`TransportConfig`、`TransportFactory`、`HttpTransport`、`GrpcTransport`、`DefaultControlPlaneService`；更新 `ControlPlaneConfigTest`、模块 README 与设计文档。
- [x] 清理旧逻辑：`extractTokenFromOtlpHeaders`、单值 `authorizationHeader`、未使用的 `getHeaders()` 均已删除；保留 Arthas 依赖的 `getAuthToken()` 并保证纯 token 语义。
- [x] 构建验证已完成：定向测试 25 个用例、全模块 23 个测试报告均 0 失败，改动文件 IDE 诊断 0 条；详见实施设计文档「构建验证结论」。
- [ ] artifact 重新发布与 javaagent 重建待执行（源码改动尚未进入线上运行的 jar）。
- [ ] 撤掉三个专属 `-D` 后做运行时复核（401 消失、token 来源变为 headers）。
- 详细变更清单、风险、验证证据与遗留问题见实施设计文档「实施记录」章节。
