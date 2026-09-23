# BillGuard Java Agent Runtime · 设计文档

日期:2026-09-22
状态:待评审
参考实现:D:\shixi\aicoding\feedback-agent-runtime(,distributed 分支)—— 本文档称「参考实现」

---

## 1. 目标与非目标

**目标**:把 BillGuard(可审计账单守卫 Agent)实现为一个**原生的 Java Agent Runtime 工程实现**,作为求职作品集(Java 岗):

- 一个通用的可审计 Agent Runtime(引擎/策略门禁/护栏/技能路由/审批状态机),BillGuard 账单域是跑在其上的业务载体
- 基建层规范使用 Spring 生态,每个依赖对应一个可指认的工程问题
- 安全与审计行为可证等价:**25 条对抗探针全量平移并通过**
- 分布式形态与 参考实现对齐:nginx + web×2 + PostgreSQL + Redis + 2×MCP server

**非目标**:

- 不做 ↔Java 实时互操作(共库共 Redis 不要求,虽然 schema 兼容使然可行)
- 不做流式输出、不做新功能 —— 范围以 distributed 分支为准
- 不追求文件/函数级翻译对应

## 2. 设计原则(优先级从高到低)

1. **契约保形,实现原生**。参考实现是行为规格书,不是翻译源。对外契约(PG schema、Redis 键语义、JSON blob 形状、API 请求/响应、探针断言的行为)形状不变;内部实现(包结构、类型建模、命名、并发原语)按 Java 工程标准重新设计。
2. **每个技术只服务一个可解释的工程问题**。不为「看起来高级」引入任何依赖;引入的每项在 §4 决策记录中写明它解决的问题与被砍的替代方案。
3. **类型建模优先**。能用 record + sealed interface + pattern matching 正规建型的,不用 Map 松散传递;`Map<String,Object>`/`JsonNode` 只出现在真正的数据契约边界(工具结果、checkpoint、JSONB 列)。
4. **状态机显式化**。并发与生命周期语义(引擎循环、审批、熔断)以显式状态机建模,迁移条件与并发语义写在代码与测试里,不靠隐式时序。

## 3. 模块与依赖方向

Maven 多模块,groupId `io.github.lxyang01`,Java 21。依赖方向严格单向,由构建系统强制:

```
agent-runtime   (纯 Java 库,零 Spring 依赖) ← 项目灵魂
   ↑                包:engine / policy / guardrail / skill / contract / llm / tool / types
billguard-web         (Spring Boot 应用)  包:io.github.lxyang01.billguard.{web,auth,storage,coordination,metrics,bills,workitem}
billguard-mcp         (Spring Boot 应用 ×2:bill-server / work-item-server)
billguard-eval        (命令行:对抗探针 + 路由评测)
```

- `agent-runtime` 不知道 Spring 的存在;`billguard-web` 负责装配(把 Spring AI 适配器、JdbcTemplate 仓储、Lettuce 协调器注入 runtime)
- **原样复用的数据资产**(拷贝,非重写):`migrations/V001` SQL(按 Flyway 约定改名)、4×SKILL.md、`routes.json`、25 条探针夹具、前端三件(index.html / app.js / styles.css → `resources/static`)

## 4. 技术决策记录

### 4.1 保留的技术(表格见 README 摘要,此处记决策理由)

| 技术 | 决策理由 | 砍掉的替代 |
|---|---|---|
| Spring Boot 3 WebMVC | HTTP 服务、DI、配置统一;同步模型与 guardrails 后置校验契合 | WebFlux(响应式破坏「final 产出后再校验/脱敏」的模型) |
| Spring Security(受限) | 「认证→CSRF→能力门禁在所有请求按确定顺序生效且仅一处配置」正是 filter chain 的领域;自定义件(TokenAuthFilter、同源 CSRF、能力 AuthorizationManager)都是它的一等扩展点 | 纯手写 FilterRegistrationBean(~40 行,零框架搏斗)。**回退条款**:M2 中若出现与默认模型搏斗超过一个工作日,切换到该方案,行为契约不变 |
| Spring AI(仅 `spring-ai-openai` ChatModel) | 维护中的 OpenAI 兼容客户端(超时/重试/模型配置),包在 `LlmClient` 接口后;不用它的 tool-calling/顾问链 —— 决策协议是 runtime 自己的契约(JSON 决策 + 系统消息内嵌工具 schema) | LangChain4j;手写 RestClient(失去维护性叙事) |
| MCP Java SDK `io.modelcontextprotocol:mcp` + `mcp-spring-webmvc` | MCP 协议实现,server 与 client 用同一个库 | Spring AI MCP starter(引入第二套 MCP 栈,不多解决任何问题) |
| Flyway | 版本化迁移与启动门禁(自写 runner 解决的同一问题) | 手写迁移 runner;JPA ddl-auto |
| `JdbcTemplate` | 恰好一次契约(`UPDATE..WHERE status='pending'` + updateCount 裁决、`FOR UPDATE` 幂等回放)需要直接控制 SQL;JPA 一级缓存/脏检查与之冲突 | JPA/Hibernate;Spring Data JDBC(不必要抽象) |
| Lettuce(Spring Data Redis 直用) | Redis 访问与 Lua 脚本原子执行,键名/脚本与 参考实现逐字一致 | Jedis;Redisson(分布式锁的实现细节与「SET NX PX + Lua 持有者校验」契约不一致) |
| Micrometer | 指标注册表,`/api/metrics` JSON 端点从 registry 生成,格式对齐 参考实现 | 手写计数器;Actuator Prometheus 暴露仅一行配置,服务于 参考实现文档里「未来接 Prometheus」的运维叙事 |
| Testcontainers | 集成测试自包含 PG/Redis(独立库随起随删),替代「连固定端口 + 手动清 13 张表」 | H2/Testcontainers 之外的一切嵌入式替身 |
| Maven 多模块 | 构建系统强制依赖方向(`agent-runtime` 无 Spring 依赖是编译期事实) | 单模块(依赖纪律只剩约定);Gradle(国内企业认知度低) |

### 4.2 明确砍掉的技术

| 技术 | 为什么砍 |
|---|---|
| resilience4j | 熔断契约是「重连退避(1s/2s/4s)耗尽即 OPEN(60s)/ half-open 单探恢复」,与滑动窗口计数语义不重合;自实现 ~100 行状态机,可被探针直测 |
| 虚拟线程 | 有界线程(16+32=48)与 503 过载语义是契约的一部分;平台线程 Tomcat 配置直接对齐 |
| GraalVM native image | 无对应工程问题 |
| Spring AI tool-calling / Advisors | runtime 的核心卖点就是自研决策协议与门禁;交给框架等于抹掉项目差异化的资产 |

## 5. Runtime 核心类型设计(本文档主体)

### 5.1 三个边界接口(一切可替换性的根源)

```java
/** 模型边界 —— 运行时对 LLM 的全部认知。生产实现 SpringAiLlmClient,测试替身 ScriptedLlm / FinalLlm。
    complete 返回值携带 usage/model(参考实现用 thread-local 传递,Java 改为值携带,多线程不串号)。 */
public interface LlmClient {
    LlmResult complete(LlmRequest request);
}
public record LlmRequest(List<ChatMessage> messages, List<Map<String, Object>> toolSchemas) {}
public record LlmResult(String raw, Map<String, Object> usage, String model) {}   // raw = 模型原文,决策解析在引擎侧

/** 工具边界 —— ToolDefinition(名称/描述/JSON Schema/策略/handler)注册进 ToolRegistry;
    多租户 owner 隔离在装配期闭包绑定(与 参考实现相同:web 装配时把 owner 捕获进 handler),
    引擎对工具结果零假设(handler 返回 Object 直通序列化,degraded:true 等键原样保留 —— runtime 设计决策,非兼容包袱)。 */
public interface ToolHandler { Object execute(Map<String, Object> arguments) throws Exception; }
public record ToolDefinition(String name, String description, Map<String, Object> parameters,
                             ToolPolicy policy, ToolHandler handler,
                             Function<Object, String> resultFormatter) {}

/** 策略边界 —— 工具调用的唯一门禁。静态裁决:read/low_write → Allowed;high_write 或 requiresApproval → ApprovalRequired;
    forbidden → PolicyException(引擎捕获后作为工具错误结果回给模型,循环不中断)。 */
public final class PolicyGateway {
    public static PolicyVerdict enforce(ToolPolicy policy, String toolName) { ... }
}
public sealed interface PolicyVerdict permits Allowed, ApprovalRequired {}
```

### 5.2 决策协议(sealed 层次,pattern matching 消化)

```java
/** 模型单步输出解析后的类型化结果;解析失败抛 DecisionParseException → run_error 失败收尾(行为契约,对齐 参考实现,不重试)。 */
public sealed interface AgentDecision permits ToolCallDecision, FinalDecision {}
public record ToolCallDecision(String thought, String tool, Map<String, Object> arguments) implements AgentDecision {}
public record FinalDecision(String thought, String answer) implements AgentDecision {}

/** 策略裁决 */
public sealed interface PolicyVerdict permits Allowed, Rejected, ApprovalRequired {}
public record ApprovalRequired(String pauseReason) implements PolicyVerdict {}

/** 运行事件:record + 事件名常量(RunEvents.*)。trace 的 wire 契约是「字符串事件名 + data map」
    (traces 表 events JSONB 形状不变),sealed 变体只增加仪式不改变行为。
    消费方(hooks)异常全部吞没 —— observability must never break the loop。 */
public record RunEvent(String eventType, String traceId, String sessionId, int step,
                       Map<String, Object> data, String timestamp) {}
```

### 5.3 引擎与调用链

```java
public final class AgentRuntime {
    AgentResponse run(Conversation conv, String userInput);      // 引擎主循环
    AgentResponse resume(ApprovalId id, Decision decision);      // 三阶段:第二→第三阶段
}
```

**run 调用链**(每步,顺序即语义):

```
validateUserInput(32k)
→ ContextBuilder.build(summary, history, activeSkills, tools)   // 字符预算裁剪
→ LlmClient.complete(...)
→ parse → AgentDecision
   ├─ ToolCall → PolicyGateway.check
   │     ├─ Allowed      → Tool.execute → evidence 记账 → 下一轮
   │     ├─ Rejected     → 拒绝原因作为 tool 结果回给模型
   │     └─ ApprovalRequired → 写审批(checkpoint v2)→ PAUSED 返回
   ├─ Final → 出口三连门禁:
   │     missingRequiredTools → missingSections → (redactPii + numericGrounding)
   │     未过 → 违规原因注入 system 消息,继续循环(上限 maxSteps=8)
   └─ Malformed → 解析错误注入 system 消息,重试
```

**resume 调用链**:加载 checkpoint → 校验 skill_versions 与 allowed_tools 无漂移 → 执行已批准的工具 → `markExecution(executed|failed)` → 继续主循环至 final。

### 5.4 四个显式状态机

**(0)引擎循环**

```
RUNNING ──final 过全部门禁──▶ COMPLETED
   │ │tool(Approved/READ_ONLY)     │final 未过门禁(注入原因, 继续循环)
   │ ▼                             └────────▶ RUNNING
   │ PAUSED_FOR_APPROVAL ──人 approve──▶ RESUMING ──▶ RUNNING
   │        └──人 reject──▶ REJECTED_FINAL(诚实收尾,不执行)
   └──maxSteps / runTimeout(120s)──▶ ABORTED
```

**(1)Harness 审批(approvals 表)**

```
pending ──decide: 条件 UPDATE WHERE status='pending',updateCount==0 即败──▶ approved / rejected
approved ──resume 执行工具──▶ executed / failed(execution_result 记执行时刻)
```
并发语义:两个并发 decide 恰好一个生效(PG 行锁);**不存在** executed 之前的第二个写路径。

**(2)工单域审批(wi_approvals + issues,与 (1) 串联成三阶段)**

```
prepare_issue(low_write) → pending(expires_at=+30min)
人 decide ──▶ approved ──commit_issue(high_write, 又触发 (1))──▶ consumed(issue_id 落库, ISS-NNNN)
pending/aproved ──超时──▶ expired        rejected: 草稿作废
commit 幂等:FOR UPDATE + consumed 已置则返回 idempotent_replay:true
```
要点:伪造 checkpoint 也过不了 (1) 的第二道闸;approve 永不作为工具暴露(通道外决策)。

**(3)MCP 连接熔断(每 server 一份)**

```
CLOSED ──连接类错误:退避 1s/2s/4s 重连 3 次耗尽──▶ OPEN(60s,全部快速失败不发包)
OPEN ──60s 到──▶ HALF_OPEN(只放一次探测) ──成功──▶ CLOSED   ──失败──▶ OPEN
并发语义:recovering 标志期间并发调用立即快速失败;熔断拒绝 → ToolResult{degraded:true},
        高风险写工具降级记**失败**不记成功(审计诚实)
```
连接类异常白名单按 Java 异常体系显式列出(IOException 家族 + ConnectException/TimeoutException + SDK 会话异常),判定规则写注释;`suppressed` 不参与判定(对齐 「只走显式因果链」)。

## 6. Web 层与安全

- **路由**:`@RestController` 平移约 30 端点(GET 5 / POST ~26);`@RestControllerAdvice` 统一异常映射:`BadRequest 400 / PermissionDenied 403 / Locked 423 / Busy 429 / Overloaded 503`;异常在业务层深处抛(锁/槽位获取点),controller 保持声明式
- **Spring Security 配置**:STATELESS(不建 HttpSession)、关默认 CSRF、三个自定义件 —— `TokenAuthFilter`(Cookie `session=` → Redis `auth:token:{sha256}` 校验,7 天 TTL 滑动续期)、`SameOriginCsrfFilter`(POST 校验 Origin/Referer 与 Host,**缺失放行**以兼容非浏览器客户端 —— 契约,与 Spring token 模式不同)、`CapabilityAuthorizationManager`(路径→能力表,等价 `_CAPABILITY_BY_PATH`)
- **登录**:PBKDF2-HMAC-SHA256 200k 迭代 + 16B salt;未知用户名跑等代价 dummy-hash 抹平计时;`MessageDigest.isEqual` 恒时比较;登录节流 = Redis Lua INCR+EXPIRE(`login:fail:{sha256(user|ip)}`,5 次/10 分钟,成功清零);「最后一个启用管理员不可降级/删除/禁用」
- **有界线程**:Tomcat `threads.max=16 / accept-count=32`(48 容量,满载 503);LLM 槽位 = Redis Lua check-and-incr(`llm:slots`,上限 4,超限 429)+ 本地 `tryAcquire` 语义对齐;会话锁 = `SET NX PX` + Lua 持有者校验释放(TTL=run_timeout+60s),冲突**立即 423 不排队**
- **指标**:Micrometer Counter/Timer/Gauge 对齐 参考实现 18 项指标;`/api/metrics` 需登录、JSON 形状对齐;Actuator Prometheus 一行配置暴露

## 7. 存储层

- Flyway:启动时迁移;「落后于 classpath 版本即拒启」对齐 `AUTO_MIGRATE` 门禁语义
- `JdbcTemplate` + record DTO;金额 `BigDecimal`;JSONB ↔ `JsonNode` 经 `PGobject` 转换器;时间戳统一 ISO-8601 字符串(对齐存量列类型,契约)
- 仓储(领域划分):`UserRepository` / `BillRepository`(账单+订阅+分类+报告+导出)/ `ApprovalRepository` / `WorkItemRepository` / `ConversationRepository`(sessions+evidence+traces 三张 JSONB 表)
- 多租户:owner 条件由仓储层强制(`owner IS NULL` 视为 admin 可见公共数据),隔离探针直测

## 8. MCP 层

- **Server ×2**(bill :8010 / work-item :8020):MCP Java SDK WebMVC transport,`@Tool` 注解声明,annotations 携带 risk_level/requires_approval;stateless;`/health`;bill server 5 只读工具 + update_status(high_write),work-item server list_issues/get_issue 只读 + prepare/commit 两段(§5.4 (2));PII 脱敏在 server 侧 note 字段强制
- **Client**:SDK sync client 包一层 `McpClientManager`(§5.4 (3) 状态机);工具注册名 `{server}.{tool}`;调用超时 20s;降级结果结构化返回不抛异常

## 9. 测试与验收

| 层 | 做法 |
|---|---|
| 单元 | JUnit5 + AssertJ;runtime(引擎/护栏/策略/技能路由/契约正则)纯 JVM 直测 |
| 集成 | Testcontainers(PG+Redis),每类独立测试库,幂等可重跑 |
| 模型替身 | `ScriptedLlm`(剧本队列)/ `FinalLlm` 实现 `LlmClient` |
| 对抗探针 | **25 条全量平移**(注入/越权/伪造数字/跨用户窃取/CSRF/节流/并发双提交/熔断降级),确定性,无需真实 Key |
| 精选单测 | ~70:并发恰好一次、锁 423、槽位 429、护栏各门禁、审批状态机全迁移、MCP 熔断全迁移 |
| CI | GitHub Actions `mvn verify`(Testcontainers),README 徽章 |

**验收标准**:25/25 探针通过;集群冒烟通过(kill web-1 故障转移、跨实例会话锁 423、同会话并发一 200 一 423)。

## 10. 部署

- docker-compose 拓扑与 参考实现一致:nginx(:8080, ip_hash)+ web×2 + postgres(16)+ redis(7)+ bill-server + work-item-server
- Java 镜像:多阶段(temurin-21 构建层 → temurin-21 JRE 运行层),按模块出镜像;健康检查改用 wget/curl(镜像内含)
- 环境变量命名保持(`BILLGUARD_PG_DSN` / `BILLGUARD_REDIS_URL` / `BILLGUARD_LLM_MODEL` 等),`.env` 用法不变

## 11. 里程碑(每个可独立验收)

1. **M1 Runtime 内核**:`agent-runtime` 全量(引擎/策略/护栏/技能/契约)+ `billguard-web` 最小装配(PG/Redis)+ 纯 JVM 单测绿 + 1 条端到端探针级路径
2. **M2 Web 全量**:30 端点 + Security 三件套 + 前端三件 + 指标端点
3. **M3 MCP 双端**:两 server + client 管理器/熔断 + 相关探针
4. **M4 证明体系**:25 探针全过 + 路由评测 + Testcontainers 集成全绿
5. **M5 生产化**:compose 集群 + 冒烟验收 + CI + README(叙事:Java Agent Runtime,BillGuard 为载体)

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| 中文正则移植语义漂移(casefold ≠ toLowerCase) | 护栏正则集中一个类 + 逐条对照单测;Locale.ROOT 显式 |
| checkpoint/schema JSON 形状走样 | 形状锁定测试(与 参考实现样本 JSON 逐字段断言) |
| Spring Security 与默认模型搏斗 | §4.1 回退条款:超一个工作日切换纯手写 filter,行为契约不变 |
| MCP Java SDK streamable-http 成熟度 | client/server 同为 Java 侧,transport 两端一致即可;必要时退 SSE,集群内自洽 |
| 恰好一次被「顺手优化」破坏(SELECT 后 UPDATE) | 审批状态机迁移测试 + code review 检查单 |
| 时间语义(ISO 字符串 vs timestamp 类型) | 统一 ISO-8601 字符串,写入转换器,禁止混用 |
