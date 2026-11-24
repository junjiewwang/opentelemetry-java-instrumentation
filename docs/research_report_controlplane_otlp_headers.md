# controlplane-extension 上报 401 与 OTEL_EXPORTER_OTLP_HEADERS 适配性分析

## Executive Summary

结论是「可以适配，而且大部分适配代码已经存在」：控制面配置类 `ControlPlaneConfig` 已经会读取 `otel.exporter.otlp.endpoint`、`otel.exporter.otlp.protocol`、`otel.exporter.otlp.headers`，并把 `otel.agent.control.*` 设计为可覆盖项，因此按当前源码，`-Dotel.agent.control.endpoint` 与 `-Dotel.agent.control.protocol` 并不是必需的，真正必需的是 `-Dotel.agent.control.token`。根因在鉴权解析：控制面自己按逗号/等号切分 headers 字符串，却没有按 W3C Baggage 规范做百分号解码，也不把 header 值当作「完整 header 值」透传，而是先剥 `Bearer ` 前缀、再由 `getAuthorizationHeader()` 重新拼接；用户合规的 `authorization=Bearer%20<TOKEN>` 因此被当成整个 token，最终发出 `Authorization: Bearer Bearer%20<TOKEN>`，服务端校验失败返回 401。修复只需对齐 SDK 数据面的解析语义，推荐把「提取 token 再拼 Bearer」改成「解码后透传完整 headers」，改完即可只保留标准 `OTEL_EXPORTER_OTLP_*` 环境变量。本轮仅做分析与方案设计，未修改任何代码。

## 背景与范围

用户给出的启动配置为 `OTEL_EXPORTER_OTLP_HEADERS=authorization=Bearer%20<TOKEN>`、`OTEL_EXPORTER_OTLP_ENDPOINT=<COLLECTOR_ENDPOINT>`、`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`、`OTEL_SERVICE_NAME=<SERVICE_NAME>`（token、域名、服务名均已脱敏）。现象是控制面 extension 上报返回 HTTP 401，必须额外加 `-Dotel.agent.control.endpoint`、`-Dotel.agent.control.token`、`-Dotel.agent.control.protocol` 才工作。

需要先澄清一个容易走偏的定位问题：`custom-extensions/controlplane-extension` 目录里没有任何 Java 源码，只有一个 `build.gradle.kts`，它声明依赖 `io.opentelemetry:opentelemetry-sdk-extension-controlplane`（`custom-extensions/controlplane-extension/build.gradle.kts:6-12`）。实现全部位于另一个仓库 `/Users/junjiewwang/IdeaProjects/github/opentelemetry-java/sdk-extensions/controlplane/`，版本由 `dependencyManagement/build.gradle.kts:8,51-52` 固定为 `1.57.0`，仓库列表里包含 `mavenLocal()`（`settings.gradle.kts:28-32`），本地 `~/.m2/repository/io/opentelemetry/opentelemetry-sdk-extension-controlplane/` 下确有 `1.57.0` 与 `1.58.0-SNAPSHOT` 两个已发布版本。也就是说，分析必须落在 SDK 仓库的控制面模块，而不是 instrumentation 仓库的包装模块。该模块通过 SDK SPI 注册（`CONTROLPLANE_EXTENSION.md:142-149`），入口是 `ControlPlaneAutoConfigurationProvider.customize()`，其中 `addTracerProviderCustomizer` 调用 `initializeControlPlane(config)`，再由 `ControlPlaneConfig.create(config)` 生成配置（`ControlPlaneAutoConfigurationProvider.java:40-73,133-143`）。

## 根因链路：配置其实读得到，错在鉴权值语义

第一层要排除的是「环境变量根本没进控制面」这个猜想。`DefaultConfigProperties.create()` 显式传入 `System.getenv()`（`DefaultConfigProperties.java:51-55`），构造函数把环境变量键归一化后与系统属性合并，优先级为系统属性 > 环境变量 > 默认属性（`DefaultConfigProperties.java:69-85`）；归一化由 `ConfigUtil.normalizeEnvironmentVariableKey()` 完成，规则是转小写并把 `_` 换成 `.`（`api/all/.../ConfigUtil.java:66-76`）。因此 `OTEL_EXPORTER_OTLP_HEADERS` 在 `ConfigProperties` 里就是 `otel.exporter.otlp.headers`，SPI 回调拿到的 `config` 能看到它，`getString()` 也实现为直接查这张表（`DefaultConfigProperties.java:97-101`）。控制面读不到环境变量的说法不成立。

第二层是 endpoint 与 protocol。二者确实已经复用标准配置：`ControlPlaneConfig.java:28-30` 定义了 `otel.exporter.otlp.endpoint` / `.protocol` / `.headers` 三个共享键，`fromConfigProperties()` 按「专属 > 共享 > 兜底」读取（`ControlPlaneConfig.java:492-517`），`build()` 在未配置时兜底为 `http/protobuf` 并按协议推导 4317/4318（`ControlPlaneConfig.java:690-729`）。设计文档 `docs/2026-05-21/protocol-default-and-logging.md:24-25` 写明同一优先级，测试 `ControlPlaneConfigTest.java:138-151`（`fallbackToOtlpWhenNoDedicatedConfig`）也断言只配 OTLP endpoint/protocol 时控制面能复用。所以按当前源码，两个 `-D` 是冗余的；它们之所以出现在用户的可用配置里，可能是沿用了旧文档或旧构建，这一点需要现场日志确认（见「验证路径」）。

第三层，也是 401 的决定性一环，是 headers。`fromConfigProperties()` 用 `properties.getString(OTLP_HEADERS)` 取到原始字符串（`ControlPlaneConfig.java:517`），构造时交给 `resolveAuthToken()` 做三级优先级解析：专属 token > `otel.resource.attributes` 里的 `token` > headers 里的 Authorization（`ControlPlaneConfig.java:170-192`）。问题出在 `extractTokenFromOtlpHeaders()`（`ControlPlaneConfig.java:222-239`）：它自己 `split(",")`、`split("=", 2)`，只判断 `value.toLowerCase(Locale.ROOT).startsWith("bearer ")` 来决定是否剥掉前缀，全程没有任何 `URLDecoder.decode`。用户写的是规范要求的 `Bearer%20<TOKEN>`，字面串并不以 `bearer ` 开头，于是整个 `Bearer%20<TOKEN>` 被当作 token 返回；随后 `getAuthorizationHeader()` 又无条件拼上 `"Bearer "`（`ControlPlaneConfig.java:342-347`），`HttpTransport.sendUnary()` 把它直接设进请求头（`HttpTransport.java:88-97`）。最终线上发出的头是 `Authorization: Bearer Bearer%20<TOKEN>`，控制面请求失败时由 `HttpTransport.java:119-128` 打出 `code=401` 并抛出 `TransportException`。反过来，专属 token 路径返回值是纯 token，`Bearer <TOKEN>` 正好是服务端期望的形态，这解释了为什么加了 `-Dotel.agent.control.token` 就能成功。现有测试只覆盖字面空格写法 `Authorization=Bearer header-token`（`ControlPlaneConfigTest.java:238-251`），没有 `%20` 用例，这个缺陷因此一直没被发现。

第四层是设计层面的语义冲突，比漏一个 decode 更本质。SDK 数据面把 `OTEL_EXPORTER_OTLP_HEADERS` 的每个值视为「完整的 HTTP header 值」：`OtlpConfigUtil.configureOtlpHeaders()` 先 `config.getMap()`，再对每个值做 `URLDecoder.decode(value, UTF-8)`，然后 `addHeader.accept(key, decoded)`（`OtlpConfigUtil.java:148-165`），`Bearer%20<TOKEN>` 会变成 `Authorization: Bearer <TOKEN>` 原样发出。控制面却把同一个值当作「可能带 Bearer 前缀的 token」再加工一遍。同一个变量在两条链路语义不一致，导致任何一边的合规写法都可能在另一边出错；即使只补一个 decode，也仍然只支持单个 Authorization 头、不支持多 header 与自定义头，且与 SDK 的解析实现形成重复代码。另外 `ControlPlaneConfig.getHeaders()`（`ControlPlaneConfig.java:321-324`）在整个模块里除自身外没有任何调用者，属于可清理的遗留 API。

规范侧的判定也支持上述结论：OTLP exporter 规范要求 headers 以 W3C Baggage 的 `key1=value1,key2=value2` 表示，且「Each configuration option MUST be overridable by a signal specific option」；W3C Baggage 规定 `baggage-octet` 之外的码点（含空格）必须百分号编码，字面 `%` 也必须编码成 `%25`。因此 `Bearer%20<TOKEN>` 是合规写法，不合规的是控制面的解析器，而不是用户的配置。SDK 自身在 resource attributes 上也用同样的 `getMap` + `URLDecoder.decode` 组合（`ResourceConfiguration.java:68-88`），进一步说明解码是 SDK 的统一约定。

控制面的请求路径是 `<endpoint>/v1/control` 加具体操作，例如 `/poll`、`/status`（`ControlPlaneConfig.java:436-445`、`Transport.java:75-107`），与数据面的 `/v1/traces` 不同，所以「数据面能发、控制面 401」并不矛盾：两者共用配置但走不同的 HTTP 请求与鉴权结果。

## 适配方案对比

| 方案 | 改动范围 | 效果 | 主要代价与风险 |
|---|---|---|---|
| 0A 零改动：headers 值改字面空格（`authorization=Bearer <TOKEN>`，环境变量需加引号） | 仅配置 | 控制面 `startsWith("bearer ")` 命中，SDK 的 `URLDecoder.decode` 对空格不做转换，两边同时可用 | 违反 W3C Baggage 的百分号编码要求，依赖实现宽容；环境变量含空格在编排系统里易被截断；未来 SDK 严格校验会失效 |
| 0B 零改动：用 `OTEL_RESOURCE_ATTRIBUTES=token=<TOKEN>` 走第二优先级 | 仅配置 | 控制面 `extractTokenFromResourceAttributes()`（`ControlPlaneConfig.java:200-213`）能取到 token | **不推荐**：`ResourceConfiguration.java:68-88` 会把 token 写进 Resource，随所有遥测数据导出，造成凭据扩散 |
| 0C 零改动：继续保留 `-Dotel.agent.control.token` | 仅配置 | 现状可用，零风险 | 配置双轨、与标准变量脱节，本次诉求未解决 |
| 1 最小修复：在 `extractTokenFromOtlpHeaders()` 里先 `URLDecoder.decode(value, UTF-8)` 再剥前缀，并补 `%20` 单测 | 1 个文件约 10 行 + 测试 | 现有写法立即生效，风险低、可快速验证 | 仍只认单个 Authorization 头、仍二次拼 `Bearer`、与 SDK 解析逻辑重复（违背 DRY）、不处理 `%2C` / `%25` / 信号级覆写 |
| 2 推荐：改为「解码后透传完整 headers」 | `ControlPlaneConfig`、`TransportConfig`、`TransportFactory`、`HttpTransport`、`GrpcTransport` 与测试 | 与 SDK 数据面/规范语义完全一致，天然支持 `Bearer%20<TOKEN>`、多 header 与自定义头；消除语义冲突与重复解析 | 改动面中等，需要端到端回归；需定义专属 token 与 headers 的优先级、`otel.agent.control.token` 仍是纯 token 语义 |
| 3 架构层：把 headers 解析抽到公共层供 `OtlpConfigUtil` 与控制面共用 | SDK 核心模块 + 控制面 | 从根本上消除两套解析实现，符合高内聚低耦合 | `OtlpConfigUtil` 位于 `exporters/otlp/all` 的 `internal` 包且自述「not for public use, APIs unstable」（`OtlpConfigUtil.java:28-32`），控制面 `build.gradle.kts:12-26` 并未依赖该模块；需新增依赖或上移工具类，影响面最大，建议单独立项 |

方案 2 的具体设计建议：用 `ConfigProperties.getMap("otel.exporter.otlp.headers")` 替代手工 `split`，对每个 value 做 `URLDecoder.decode(value, UTF-8)`（与 `OtlpConfigUtil.java:154-163` 一致，非法编码抛 `ConfigurationException`），把结果作为 `Map<String,String>` 存入配置并经 `TransportConfig` 传给两个 Transport，由它们一次性 `addHeader`；保留三级优先级，让 `otel.agent.control.token` 作为最高优先级覆盖（拼成 `Bearer <token>`），其余情况直接透传解码后的 headers；删除 `extractTokenFromOtlpHeaders()` 与未被使用的 `getHeaders()`。控制面不是 OTLP 信号，是否参与 `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_HEADERS` 信号级覆写应当显式定义并写进文档，建议明确「只消费通用变量」。日志方面，`ControlPlaneConfig.java:150-152` 已只打印 token 来源不打印值，这个安全边界要保持；401 日志（`HttpTransport.java:119-128`）也应保留。

发布链上有一个容易被忽略的前提：控制面逻辑来自 `mavenLocal` 的 `1.57.0` artifact，而 SDK 工作区版本也正好是 `1.57.0`（`version.gradle.kts:1-12`）。改完 SDK 源码必须重新发布到本地仓库或升版本并同步 `dependencyManagement/build.gradle.kts:8`，再重建 javaagent，否则改动不会进入运行时。

## 验证路径与现场自证

最省事的现场自证是启动日志，不需要抓包。`build()` 会打印 `[CONTROL-PLANE] [ENDPOINT_RESOLVED] endpoint=..., protocol=..., source=...` 与 `[CONTROL-PLANE] [PROTOCOL_RESOLVED] protocol=..., source=...`，`source` 明确标注 `otel.exporter.otlp.endpoint (shared)` 还是 `dedicated`（`ControlPlaneConfig.java:697-725,735-751`）；`Auth token configured from source: ...`（`ControlPlaneConfig.java:150-152`）会显示 token 到底来自专属配置、resource attributes 还是 headers。如果 source 显示 shared 而 token 来源缺失或错误，就与本文结论完全吻合。

修复后的验证清单应包含：`Bearer%20<TOKEN>` 解码为 `Bearer <TOKEN>` 且不重复拼前缀、无 Bearer 前缀的裸 token、多 header 与自定义头、`%2C` / `%25`、空值与非法编码、专属 token 优先级不被破坏、专属 endpoint/protocol 覆盖仍生效；再加一条端到端断言，直接检查控制面请求实际发出的 `Authorization` 值，而不是只断言 `getAuthToken()` 的返回值——现有测试正是只断言了后者，才漏掉了这个 bug。

## Analysis / Synthesis

把三条证据链放在一起看：SDK 数据面（autoconfigure → `OtlpConfigUtil` → exporter builder）对标准变量的处理是完整且符合规范的；控制面（同一个 `ConfigProperties` → `ControlPlaneConfig` → 自建 OkHttp 传输）在 endpoint/protocol 上做了正确的共享复用，却在 headers 上另起炉灶，且没有遵循同一套解码与透传约定。因此这不是「extension 无法读取标准配置」，而是「读取了但解析语义不一致」，这也解释了为什么加一个专属 token 就能绕过问题、以及为什么三个 `-D` 里只有 token 真正起作用。修复方向应当是收敛到 SDK 已有的语义，而不是再增加一组专属配置项；方案 1 能止血但会保留重复实现与语义分歧，方案 2 在改动量与长期可维护性之间最平衡，方案 3 是更彻底但跨仓库、跨模块的架构改造，适合后续单独推进。

## Conclusion

可以适配 `OTEL_EXPORTER_OTLP_HEADERS`，且无需新增配置项。按当前工作区源码，`OTEL_EXPORTER_OTLP_ENDPOINT` 与 `OTEL_EXPORTER_OTLP_PROTOCOL` 已被控制面复用，两个对应 `-D` 冗余；401 的直接原因是 `ControlPlaneConfig.extractTokenFromOtlpHeaders()` 未做百分号解码且把完整 header 值误当 token 再加工，导致发出 `Bearer Bearer%20<TOKEN>`。推荐按方案 2 把控制面鉴权改为「解码后透传完整 headers」，配合单测、端到端断言与重新发布 `1.57.0` artifact；修复后用户只需四个标准环境变量，不再需要任何 `otel.agent.control.*` 参数。修复已于 2026-11-24 实施，源码改动与测试见 `docs/implementation_plan_controlplane_headers_passthrough.md` 的实施记录；需注意改动须重新发布 `opentelemetry-sdk-extension-controlplane` artifact 并重建 javaagent 后才会在线上进程生效。

## Limitations

有三点限制。其一，运行时证据已在 2026-11-24 复核补齐：`arthas_jad` 反编译的 `ControlPlaneConfig` 与工作区源码逐行一致（由 `ExtensionClassLoader` 加载，未被 shading 重命名），因此「运行时 jar 与源码不一致」的风险已排除，本文关于 endpoint/protocol 两个 `-D` 冗余的判断适用于该线上进程；脱敏 `ognl` 断言证实输入为 `Bearer%20<TOKEN>` 且 `startsWith("bearer ")` 返回 false，`vmtool` 读到运行时 `getAuthTokenSource()` 为 `otel.agent.control.token (dedicated)`。复核细节见 `docs/implementation_plan_controlplane_headers_passthrough.md`。其二，线上进程仍未抓包到实际发出的 `Authorization` 值，「双 Bearer」结论由运行时代码、运行时输入与源码链路共同推导；修复后已补端到端测试（Armeria 假服务端）断言真实请求头，但该断言发生在本地测试环境而非线上进程。其三，没有 collector 服务端代码，无法排除服务端对 `/v1/control` 路径另有鉴权策略；从现有证据看，客户端侧的头值错误已足以解释 401。另外，`arthas_status` 持续 `not_attached`（`tunnelReady: false`）但结构化命令桥可用，该通道配置问题未追查；计划中要求纳入的 `wechat-article-search` 技能在当前会话可用技能清单中不存在，中文社区旁证改由网络检索替代，官方规范与本地源码仍是一手依据。

## References

1. [OpenTelemetry Protocol Exporter 规范（headers 与 signal-specific 覆写）](https://opentelemetry.io/docs/specs/otel/protocol/exporter/)
2. [opentelemetry-specification / specification/protocol/exporter.md](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/protocol/exporter.md)
3. [W3C Baggage Header Content（percent-encoding 规则）](https://www.w3.org/TR/baggage/#header-content)
4. [OpenTelemetry SDK Environment Variables 规范](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/)
5. [ControlPlaneConfig.java（配置读取、token 解析、Authorization 组装）](../opentelemetry-java/sdk-extensions/controlplane/src/main/java/io/opentelemetry/sdk/extension/controlplane/config/ControlPlaneConfig.java)
6. [ControlPlaneConfigTest.java（token 优先级与 headers fallback 测试）](../opentelemetry-java/sdk-extensions/controlplane/src/test/java/io/opentelemetry/sdk/extension/controlplane/config/ControlPlaneConfigTest.java)
7. [ControlPlaneAutoConfigurationProvider.java（SPI 入口）](../opentelemetry-java/sdk-extensions/controlplane/src/main/java/io/opentelemetry/sdk/extension/controlplane/spi/ControlPlaneAutoConfigurationProvider.java)
8. [HttpTransport.java（Authorization 头设置与 401 日志）](../opentelemetry-java/sdk-extensions/controlplane/src/main/java/io/opentelemetry/sdk/extension/controlplane/client/transport/HttpTransport.java)
9. [OtlpConfigUtil.java（SDK headers 解码实现）](../opentelemetry-java/exporters/otlp/all/src/main/java/io/opentelemetry/exporter/otlp/internal/OtlpConfigUtil.java)
10. [DefaultConfigProperties.java（环境变量与系统属性合并）](../opentelemetry-java/sdk-extensions/autoconfigure-spi/src/main/java/io/opentelemetry/sdk/autoconfigure/spi/internal/DefaultConfigProperties.java)
11. [protocol-default-and-logging.md（统一端点设计文档）](../opentelemetry-java/sdk-extensions/controlplane/docs/2026-05-21/protocol-default-and-logging.md)
12. [controlplane-extension/build.gradle.kts（依赖声明）](custom-extensions/controlplane-extension/build.gradle.kts)
