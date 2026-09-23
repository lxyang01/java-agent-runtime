# 开发手册

本手册面向本仓库的开发者(以及未来的自己):环境、构建、测试、模块地图、契约清单、排障。随每个里程碑更新。

## 1. 环境

| 组件 | 要求 | 备注 |
|---|---|---|
| JDK | 21(Temurin) | 本机装在 `~/tools/jdk-21.0.12.1+1` |
| Maven | 3.9+ | `~/tools/apache-maven-3.9.9`;每次构建前 `. ~/tools/javaenv.sh` |
| Docker Desktop | 运行中 | Testcontainers 集成测试需要;Windows 下需手动启动 |
| 测试库 | 无需预建 | PG/Redis 由 Testcontainers 随测随起 |

## 2. 构建与测试

```bash
. ~/tools/javaenv.sh          # 或把两个 bin 目录写进系统 PATH
mvn verify                    # 全模块:编译 + 单测 + 集成测试
mvn -pl agent-runtime test    # 仅 runtime(纯 JVM,不需要 Docker)
mvn -pl billguard-web test -Dtest=PgStoresTest    # 单个测试类
```

- agent-runtime 的测试**不需要 Docker**;billguard-web 的仓储/协调/端到端测试需要(Docker 未运行时自动跳过,`@Testcontainers(disabledWithoutDocker = true)`)
- 测试 JVM 强制 UTF-8(父 POM surefire argLine),中文断言/文案不受 Windows 默认编码影响

## 3. 模块地图

### agent-runtime(`io.github.lxyang01.agent`)

| 包 | 职责 | 关键类型 |
|---|---|---|
| `engine` | 引擎主循环与配置 | `AgentRuntime`(run/resume/finalizeRejection)、`AgentSpec`、`ContextBuilder`、`DecisionParser`、`AgentDecision`(sealed) |
| `guardrail` | 输入/输出护栏 | `Guardrails`(长度上限/PII 脱敏/数字 grounding) |
| `policy` | 工具风险门禁 | `PolicyGateway`、`ToolPolicy`、`PolicyVerdict`(sealed: Allowed/ApprovalRequired) |
| `tool` | 工具注册与校验 | `ToolRegistry`、`ToolDefinition`、`ToolHandler` |
| `skill` | 技能发现与路由 | `SkillRuntime`、`SkillSource`、`Trigger`(sealed: Word/AllWords) |
| `contract` | 用户原话动态契约 | `RequestContracts`(编译器)、`RequestContract` |
| `store` | 存储端口 | `ConversationStore`、`ApprovalStore`、`TraceWriter` |
| `llm` | 模型边界 | `LlmClient`、`LlmRequest`、`LlmResult`(usage 值携带,无 thread-local) |
| `testing` | 确定性替身 | `ScriptedLlm`、`FinalLlm`、`InMemory*Store` |
| `types` | 会话与事件 | `Conversation`、`ChatMessage`、`RunEvent`、`RunEvents`(23 事件名常量) |

**引擎状态机**(spec §5.4):`RUNNING →(final 过三连门禁)COMPLETED |(高写)PAUSED_FOR_APPROVAL →人批准→ RESUMING → RUNNING | maxSteps/超时 → ABORTED`;审批状态机 `pending → approved/rejected → executed/failed` 由存储层条件 UPDATE 保证并发恰好一次。

### billguard-web(`io.github.lxyang01.billguard`)

| 包 | 职责 |
|---|---|
| `web` | REST 全量(`AuthController`/`ApiController`/`MetricsController`)+ `ApiExceptionHandler`(400/401/403/423/429/500) |
| `security` | `TokenAuthFilter`/`SameOriginCsrfFilter`/`PathCapabilities`(16 条)/`SecurityConfig` |
| `auth` | `Authenticator`/`PgUserStore`(经 storage)/`SessionCookies`/`Capabilities` |
| `bills` | `BillFilters`/`BillPii`/`CsvImport`/`BillTools`(五工具)/`BillAgentFactory`(12 条指令) |
| `storage` | PG:`PgConversationStore`/`PgApprovalStore`/`PgTraceWriter`/`PgTraceReader`/`PgEvidenceStore`/`PgUserStore`/`BillRepository`/`BillAnomalies` + `PgJson` |
| `coordination` | Redis:`RedisSessionLock`/`RedisLlmLimiter`/`RedisAuthSessions`/`RedisLoginThrottle` |
| `core` | `BillGuardFacade`(chat/审批/快照编排,锁+槽位+证据链) |
| `llm` | `SpringAiLlmClient`(wire 改写/schema 注入/json_object) |
| `metrics` | `AppMetrics`(Micrometer 扁平命名)/`MetricsFilter` |
| `skills` | `ClasspathSkillSource`(fat jar 内技能资产) |
| `cli` | `UsersCli`(管理员播种) |
| `mcp` | `McpClientManager`(熔断/重连/降级)、`OwnerIdentity`(身份注入) |
| `config` | Spring 装配(`RuntimeConfig`) |

## 4. 契约清单(不得改写;修改前先读 spec §2「契约保形」)

1. **PG schema**:`billguard-web/src/main/resources/db/migration/V001__init.sql`;改表只能发新版本迁移文件,不改历史文件
2. **Redis 键与 Lua**:`lock:session:{sha256(session_id)}`、`llm:slots`;脚本在 `coordination` 两类里逐字
3. **trace 事件**:23 个事件名见 `RunEvents`;traces 表 events JSONB 形状 `{timestamp,event,trace_id,step,agent,**data}`
4. **checkpoint v2 字段**:`AgentRuntime.pauseForApproval` 内 LinkedHashMap 键序即契约
5. **API 状态字符串**:`completed/failed/approval_pending/rejected`;审批 `pending/approved/rejected/executed/failed`
6. **中文文案**:PROTOCOL、门禁 system 消息、AgentResponse 答案(含全角标点)逐字 —— 它们是对抗探针断言对象
7. **时间戳**:`Timestamps.nowIso()` 固定微秒 + `+00:00`(字典序可比;PG 列为 TEXT)
8. **字符串长度/截断**按 code point(`Strings.len/truncate`),PII 订单号正则带 `UNICODE_CHARACTER_CLASS`(测试锁定)

## 5. 测试策略

- **单元**(纯 JVM):runtime 全部分支;关键对齐点有专属锁定测试(如 Unicode 边界、code point、时间戳形状)
- **集成**(Testcontainers):`PgTestBase` 共享单例 PG+Redis 容器 + Flyway 迁移 + 每测试清表;`M1EndToEndTest` 用独立容器走真实 Spring 上下文
- **模型替身**:`ScriptedLlm`(剧本队列)/`FinalLlm` 实现 `LlmClient`,确定性、零 Key
- 并发语义测试:`PgStoresTest.concurrent_decide_exactly_once`(双线程屏障同步,断言一胜一拒)

## 6. 排障速查

| 症状 | 原因与处置 |
|---|---|
| Testcontainers 全部 skip,日志 `BadRequestException Status 400` | Testcontainers 版本被 Boot BOM 压回 1.21.x(与 Docker 28 npipe 不兼容)。父 POM 已把 `testcontainers-bom 2.0.5` 排在 `spring-boot-dependencies` **之前** —— 先声明者胜,勿调换顺序 |
| ryuk 镜像拉取超时 | 国内网络;surefire 已注入 `TESTCONTAINERS_RYUK_DISABLED=true`,容器由测试基座自管 |
| 中文断言乱码/失败 | 确认走 `mvn`(surefire argLine 强制 UTF-8),勿用 IDE 默认编码跑 |
| `java`/`mvn` not found | Git Bash 会话未 source `~/tools/javaenv.sh` |
| health 测试失败且本地无 PG | `HealthControllerTest` 是 `@WebMvcTest` 切片,不连库;若被改成 `@SpringBootTest` 需提供数据源或排除自动配置 |

## 7. 环境变量(billguard-web)

| 变量 | 缺省 | 说明 |
|---|---|---|
| `BILLGUARD_PG_URL` | `jdbc:postgresql://localhost:5432/billguard` | PG JDBC 地址 |
| `BILLGUARD_PG_USER` / `BILLGUARD_PG_PASSWORD` | `billguard`/`billguard` | 凭据 |
| `BILLGUARD_REDIS_URL` | `redis://localhost:6379/0` | Lettuce 连接串 |
| `billguard.llm-slots`(属性) | 4 | LLM 并发槽位上限 |

LLM:`API_KEY`(或 `OPENROUTER_API_KEY`/`OPENAI_API_KEY`)、`BILLGUARD_LLM_BASE_URL`(默认 OpenRouter)、`BILLGUARD_LLM_MODEL`(默认 openai/gpt-4.1-mini);`BILLGUARD_SECURE_COOKIES`∈{1,true} 时 Cookie 加 Secure。

## 8.5 播种管理员

```bash
echo 'Your-Password-1' | java -jar billguard-web/target/billguard-web-1.0.0-SNAPSHOT.jar   --users add admin --role admin --password-stdin   # 需先配好 PG/Redis 环境变量
```

## 8. 里程碑档案

- **M1(2026-09-22 完成)**:计划 `docs/plans/2026-09-22-m1-runtime-core.md`;runtime 149 测试;端到端:高写工具 → 审批暂停 → 批准 → resume → executed → completed,真实 PG/Redis。
- **M5(2026-09-23 完成)**:计划 `docs/plans/2026-09-23-m5-production.md`;docker/`Dockerfile`(maven 构建层 → temurin-21-jre + curl 运行层,web.jar 与 mcp.jar 双构件)、`docker-compose.yml`(7 服务:nginx ip_hash/max_fails/proxy_next_upstream/Host 透传/X-Real-IP;PG 5433 与 Redis 6380 仅本机;profile 选择 MCP server)、`docker/nginx.conf`、`.github/workflows/ci.yml`(mvn verify + 对抗报告 artifact)。运维:启停 `docker compose up -d / down / down -v`;备份 `docker compose exec -T postgres pg_dump -U billguard billguard > backup.sql`;故障转移演练 `docker compose kill web-1`;扩容 = 加 web-N + nginx upstream 一行。
- **M4(2026-09-23 完成)**:计划 `docs/plans/2026-09-23-m4-probes.md`;新增 `billguard-eval` 模块 —— 25 条对抗探针(adv-001..025)全量平移并全绿(26 项含目录校验),分布在三个探针类:引擎级 9(EngineProbesTest)、审批/门禁 9(ApprovalProbesTest)、身份/隔离/HTTP 7(WebProbesTest,含真实 MockMvc 的登录暴破/CSRF/路径穿越);`AdversarialCatalog` 保存逐字目录(id/类别/严重度/标题/攻击/期望/修复建议)并生成报告(evaluations/adversarial-v1-report.json);adv-020(文档沙箱)以静态资源路径边界等价验证并记录于目录。
- **M3(2026-09-23 完成)**:计划 `docs/plans/2026-09-23-m3-mcp.md`;新增 `billguard-domain`(账单/工单/存储/认证/协调,web 与 mcp 共用)与 `billguard-mcp`(bill :8010 / work-item :8020,profile 选择,`--spring.profiles.active=bill|work-item`)模块;MCP Java SDK 0.18.4(WebMvcStatelessServerTransport + HttpClientStreamableHttpTransport);熔断状态机(1s/2s/4s 重连×3 → OPEN 60s → 半开单探);owner 身份注入(schema 隐藏 + 参数白名单 + 强制覆盖);commit_issue 双闸审批 + 卡片工单补全。测试:domain 74 + mcp 6 + web 40(+ runtime 149)。
- **M2(2026-09-22 完成)**:计划 `docs/plans/2026-09-22-m2-web.md`;web 100 测试(安全 11/账单域 33/编排 6/LLM 适配 2/API 集成 14/指标 2 等);安全三件套 + 30 端点 + 前端 + Micrometer。chat 经 HTTP 的真实模型链路需 API Key(容器外 `--users add` 播种后 `docker compose up` 体验),集成测试以 Facade+ScriptedLlm 覆盖同等编排语义。
