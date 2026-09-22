# M1 · Runtime 内核 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 billguard-java 的 Maven 多模块骨架,完整实现 agent-runtime(引擎/策略/护栏/技能/契约/解析),billguard-web 完成 PG/Redis 最小装配,纯 JVM 单测全绿 + 一条 Testcontainers 端到端审批暂停→恢复路径。

**Architecture:** 严格单向依赖 `billguard-web → agent-runtime`;runtime 为纯 Java 库(仅 Jackson 依赖,零 Spring),引擎循环/门禁/审批状态机与 Python 版行为契约逐条对齐;PG 仓储用 JdbcTemplate + Flyway(V001 SQL 原样拷贝),Redis 协调用与 Python 逐字相同的键名与 Lua。

**Tech Stack:** Java 21、Maven 多模块、Jackson、Spring Boot 3.5.x(web 模块)、Flyway、Lettuce、Testcontainers(PG 16 + Redis 7)、JUnit5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-22-java-agent-runtime-design.md`(2026-09-22 已按规划修正 4 处)

## Global Constraints

- Java 21(`maven.compiler.release=21`),groupId `io.github.lxyang01`,版本 `1.0.0-SNAPSHOT`
- runtime 包根 `io.github.lxyang01.agent.*`;web 包根 `io.github.lxyang01.billguard.*`
- **agent-runtime 不得引入任何 Spring 依赖**(编译期由 Maven 保证);仅 Jackson databind
- 版本地板(可用更新的补丁版):spring-boot 3.5.7 / junit-bom 5.12.2 / assertj 3.27.3 / testcontainers 1.21.3;Jackson、Flyway、Lettuce、PG 驱动版本由 Boot BOM 管理
- **契约保形清单**(逐字对齐 Python,任何任务不得改写):
  - trace 事件名与字段:`run_start/run_resume/model_start/model_output/model_decision/model_output_blocked/run_error/completion_blocked/output_contract_blocked/output_redacted/grounding_blocked/tool_start/tool_end/tool_error/argument_blocked/approval_pending/approval_rejected/skill_activated/skill_error/history_compressed/run_timeout/max_steps/run_end`
  - checkpoint 字段:`schema_version(=2)/full_tool_payloads/session_id/trace_id/user_input/step/call_id/skill_versions/allowed_tools/execution_summaries/artifact_paths/completed_tools`
  - 状态字符串:`completed/failed/approval_pending/rejected`;审批状态 `pending/approved/rejected/executed/failed`
  - Redis 键:`lock:session:{sha256(session_id)}`、`llm:slots`;Lua 脚本逐字(coordination.py)
  - PG 表结构 = `migrations/V001_init.up.sql` 原文,只改文件名为 `V001__init.sql`(Flyway 约定)
  - 中文文案:PROTOCOL、门禁 system 消息、AgentResponse 答案文案(含全角标点)逐字
  - `AgentSpec` 校验错误消息逐字(`agent name cannot be empty` 等)
- 时间戳统一 `Timestamps.nowIso()`:`yyyy-MM-dd'T'HH:mm:ss.SSSSSS+00:00`(对齐 Python `datetime.now(timezone.utc).isoformat()`,字典序可比;PG 列为 TEXT)
- 字符串长度/截断按 **code point** 计(`Strings.len`/`Strings.truncate`,对齐 Python `len()`/切片)
- 中文正则移植:PII `order_id` 模式须加 `Pattern.UNICODE_CHARACTER_CLASS`(对齐 Python `\w` 含中文,否则 `\b` 语义漂移)
- 每个任务:先写失败测试 → 跑红 → 最小实现 → 跑绿 → commit。命令在仓库根 `D:\shixi\aicoding\billguard-java` 执行
- Python 参考源:`D:\shixi\aicoding\feedback-agent-runtime\billguard\`(下称 PY)

---

### Task 0: 环境验证

- [ ] **Step 0.1** 验证 JDK 21 与 Maven:

```bash
java -version   # 期望 21.x
mvn -version    # 期望 3.9+
```

缺则安装:Temurin 21(`winget install EclipseAdoptium.Temurin.21.JDK`)+ Maven 3.9(解压 zip 配 PATH)。

---

### Task 1: Maven 骨架 + /api/health

**Files:**
- Create: `pom.xml`(父)、`agent-runtime/pom.xml`、`billguard-web/pom.xml`
- Create: `billguard-web/src/main/java/io/github/lxyang01/billguard/BillguardApplication.java`
- Create: `billguard-web/src/main/java/io/github/lxyang01/billguard/web/HealthController.java`
- Create: `billguard-web/src/main/resources/application.yml`
- Create: `.gitignore`
- Test: `billguard-web/src/test/java/io/github/lxyang01/billguard/web/HealthControllerTest.java`

**Interfaces:**
- Produces: 可编译的多模块工程;`GET /api/health → {"ok":true}`

- [ ] **Step 1.1** 写父 POM(modules + dependencyManagement:Boot BOM/junit-bom/assertj/testcontainers + surefire 3.5.2 pluginManagement)
- [ ] **Step 1.2** `agent-runtime/pom.xml`:parent + 依赖 `com.fasterxml.jackson.core:jackson-databind`(版本随 Boot BOM);test:junit-jupiter、assertj-core
- [ ] **Step 1.3** `billguard-web/pom.xml`:parent + 依赖 `agent-runtime`、`spring-boot-starter-web`、`spring-boot-starter-jdbc`、`spring-boot-starter-data-redis`、`flyway-core`、`flyway-database-postgres`、`org.postgresql:postgresql`(runtime);test:`spring-boot-starter-test`、`org.testcontainers:postgresql`、`org.testcontainers:junit-jupiter`;插件 `spring-boot-maven-plugin`
- [ ] **Step 1.4** 应用类 + HealthController + application.yml(`spring.application.name: billguard-web`;M1 暂不连库,`spring.autoconfigure.exclude` 排除 DataSource/Redis/Flyway 自动配置,后续任务移除)
- [ ] **Step 1.5** 写失败测试:

```java
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {
    @Autowired MockMvc mvc;
    @Test void health_returns_ok() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }
}
```

- [ ] **Step 1.6** `mvn -q verify` 全绿
- [ ] **Step 1.7** Commit:`feat(build): Maven 多模块骨架与 /api/health`

---

### Task 2: V001 迁移 + Timestamps/Strings 工具 + PG 测试基座

**Files:**
- Create: `billguard-web/src/main/resources/db/migration/V001__init.sql`(内容 = PY `migrations/V001_init.up.sql` 原文逐字)
- Create: `agent-runtime/src/main/java/io/github/lxyang01/agent/types/Timestamps.java`
- Create: `agent-runtime/src/main/java/io/github/lxyang01/agent/util/Strings.java`
- Create: `agent-runtime/src/main/java/io/github/lxyang01/agent/util/Json.java`
- Create: `billguard-web/src/test/java/io/github/lxyang01/billguard/PgTestBase.java`
- Test: `agent-runtime/src/test/java/io/github/lxyang01/agent/types/TimestampsTest.java`、`.../util/StringsTest.java`

**Interfaces:**
- Produces: `String Timestamps.nowIso()`、`String Timestamps.iso(Instant)`;`int Strings.len(String)`(code points)、`String Strings.truncate(String, int)`;`Json.MAPPER`(ObjectMapper 单例,FAIL_ON_UNKNOWN_PROPERTIES=false)、`Json.write(Object)`(非 ASCII 不转义)、`Json.writePretty(Object)`;`PgTestBase`(静态启动 PostgreSQLContainer `postgres:16-alpine`,Flyway 程序化迁移到独立测试库,提供 `JdbcTemplate` 与清表方法)

- [ ] **Step 2.1** 拷贝迁移:`cp "D:/shixi/aicoding/feedback-agent-runtime/migrations/V001_init.up.sql" billguard-web/src/main/resources/db/migration/V001__init.sql`(不改内容)
- [ ] **Step 2.2** 失败测试:

```java
class TimestampsTest {
    @Test void nowIso_matches_python_shape() {
        assertThat(Timestamps.nowIso())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+00:00");
    }
    @Test void iso_is_lexicographically_sortable() {
        assertThat(Timestamps.iso(Instant.parse("2026-01-02T03:04:05.123456Z")))
            .isEqualTo("2026-01-02T03:04:05.123456+00:00");
    }
}
class StringsTest {
    @Test void len_counts_code_points() {
        assertThat(Strings.len("a😀b")).isEqualTo(3);   // Python len() 语义
    }
    @Test void truncate_is_code_point_safe() {
        assertThat(Strings.truncate("a😀b", 2)).isEqualTo("a😀");
    }
}
```

- [ ] **Step 2.3** 跑红 → 实现:

```java
public final class Timestamps {
    private static final DateTimeFormatter F =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    public static String nowIso() { return iso(Instant.now()); }
    public static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC).format(F);
    }
    private Timestamps() {}
}
```

```java
public final class Strings {
    public static int len(String s) { return s.codePointCount(0, s.length()); }
    public static String truncate(String s, int max) {
        if (len(s) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
    private Strings() {}
}
```

`Json`:`MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()`;`write` = `MAPPER.writeValueAsString`(UTF-8 天然非转义);`writePretty` 用默认 pretty printer。

- [ ] **Step 2.4** `PgTestBase`:

```java
@Testcontainers
public abstract class PgTestBase {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).withReuse(true);
    static JdbcTemplate jdbc;
    @BeforeAll static void start() {
        PG.start(); REDIS.start();                     // PG 用单例容器手动管理生命周期
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }
    @AfterEach void clean() {
        for (String t : new String[]{"approvals","wi_approvals","issues","traces","evidence",
                "sessions","reports","imports","tx_audits","subscriptions","categories",
                "transactions","users"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }
    static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0";
    }
}
```

- [ ] **Step 2.5** 跑绿(`mvn -q -pl agent-runtime test`;PgTestBase 由后续任务使用)→ Commit:`feat(runtime): 时间戳/长度工具(对齐 Python 语义)+ V001 迁移与 PG 测试基座`

---

### Task 3: 核心类型(types 包)

**Files:**
- Create: `agent-runtime/.../types/ChatRole.java`、`ChatMessage.java`、`Conversation.java`、`ToolCall.java`、`AgentResponse.java`、`RunEvent.java`、`RunEvents.java`
- Test: `.../types/ChatMessageTest.java`、`ConversationTest.java`

**Interfaces(后续任务全部依赖,签名不得漂移):**
- `enum ChatRole { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }`,`@JsonValue String wire()`、`@JsonCreator static ChatRole from(String)`
- `record ChatMessage(ChatRole role, String content, String name, String toolCallId)`:工厂 `system/user/assistant(String)`、`tool(String name, String content, String callId)`;`Map<String,Object> asMap()`(键 `role/content/name?/tool_call_id?`,null 省略 — 会话 JSONB 契约);Jackson 原生支持 record 反序列化
- `final class Conversation`:`getSessionId()/getSummary()/setSummary(String)/getOwner()/addMessage(ChatMessage)/List<ChatMessage> messages()(不可变视图)/replaceMessages(List)/withSummaryAndMessages(String, List)`(返回新实例,压缩用)、静态 `newConversation(String sessionId)`
- `record ToolCall(String name, Map<String,Object> arguments)`
- `record AgentResponse(String answer, int steps, String traceId, List<String> activeSkills, String status, Map<String,Object> approval)` + 常量类 `AgentStatuses { COMPLETED="completed", FAILED="failed", APPROVAL_PENDING="approval_pending", REJECTED="rejected" }`
- `record RunEvent(String eventType, String traceId, String sessionId, int step, Map<String,Object> data, String timestamp)` + 工厂 `RunEvent.of(String eventType, String traceId, String sessionId, int step, Map<String,Object> data)`(timestamp=nowIso)
- `final class RunEvents` 常量:Global Constraints 清单中 23 个事件名(如 `RUN_START="run_start"`)

- [ ] **Step 3.1** 失败测试:ChatMessage asMap 形状(role 小写、name/tool_call_id 缺省省略)、Jackson 序列化/反序列化 round-trip、Conversation addMessage/不可变视图抛 UnsupportedOperationException、replaceMessages 后原实例不受影响
- [ ] **Step 3.2** 跑红 → 实现 → 跑绿
- [ ] **Step 3.3** Commit:`feat(runtime): 会话与事件核心类型`

---

### Task 4: 决策解析器

**Files:**
- Create: `agent-runtime/.../engine/AgentDecision.java`(sealed)、`DecisionParser.java`、`DecisionParseException.java`
- Test: `.../engine/DecisionParserTest.java`

**Interfaces:**
- Produces:`sealed interface AgentDecision permits ToolCallDecision, FinalDecision`;`record ToolCallDecision(String thought, String tool, Map<String,Object> arguments)`;`record FinalDecision(String thought, String answer)`;`static AgentDecision DecisionParser.parse(String text)`(失败抛 `DecisionParseException("LLM output must contain exactly one valid tool_call or final answer")`)

- [ ] **Step 4.1** 失败测试(逐分支对齐 PY `parser.py`):

```java
class DecisionParserTest {
    @Test void plain_tool_call() {
        var d = DecisionParser.parse("{\"thought\":\"查\",\"tool_call\":{\"name\":\"bill.query\",\"arguments\":{\"limit\":5}}}");
        assertThat(d).isInstanceOf(ToolCallDecision.class);
        var c = (ToolCallDecision) d;
        assertThat(c.tool()).isEqualTo("bill.query");
        assertThat(c.arguments()).containsEntry("limit", 5);
    }
    @Test void fenced_json_preferred() { /* ```json {...}``` 前缀噪声 → 解析围栏内 */ }
    @Test void leading_noise_lenient_from_first_brace() { /* "答案如下:{\"final\":\"x\"}" */ }
    @Test void tool_calls_array_takes_first() { }
    @Test void openai_function_form() { /* {"tool_call":{"function":{"name":"t","arguments":"{\"a\":1}"}}} arguments 为字符串时解析 */ }
    @Test void final_field_wins_over_bare_object() { }
    @Test void answer_alias() { /* {"answer":"ok"} → FinalDecision */ }
    @Test void non_string_final_dumped_pretty() { /* {"final":{"a":1}} → answer 为 pretty JSON */ }
    @Test void bare_object_without_protocol_keys_is_final() { /* {"a":1} → final */ }
    @Test void array_of_results_is_final() { /* [{"a":1},{"b":2}] → final=pretty 数组 */ }
    @Test void array_with_protocol_keys_takes_first() { /* [{"tool_call":...},...] */ }
    @Test void thought_only_object_is_invalid() { /* {"thought":"x"} → DecisionParseException */ }
    @Test void garbage_throws() { }
    @Test void tool_call_precedence_over_final() { /* 两者同现 → ToolCall */ }
}
```

- [ ] **Step 4.2** 跑红 → 实现(要点:候选序列 = [围栏组1, strip 后全文];宽松解析用 `JsonFactory.createParser` + `parser.nextToken()` + `MAPPER.readTree(parser)` 忽略尾部,对齐 `raw_decode`;协议键集合 `thought/tool_call/tool_calls/final/answer`;空数组或首元素非对象 → 下一候选)

- [ ] **Step 4.3** 跑绿 → Commit:`feat(runtime): 模型决策解析器(宽松 JSON + 协议分支)`

---

### Task 5: 护栏

**Files:**
- Create: `agent-runtime/.../guardrail/Guardrails.java`、`GuardrailException.java`、`Redaction.java`
- Test: `.../guardrail/GuardrailsTest.java`

**Interfaces:**
- Produces:`Guardrails.validateUserInput(String)`/`validateModelOutput(String)`(超限抛 `GuardrailException`,限值 32_000/64_000 code points,消息 `user input exceeds the 32,000 character limit` 样式);`Redaction redactPii(String)`(`record Redaction(String text, Map<String,Integer> counts)`);`List<Double> unsupportedNumericClaims(String answer, List<Object> evidenceValues)`

- [ ] **Step 5.1** 失败测试:

```java
class GuardrailsTest {
    @Test void input_over_32k_code_points_rejected() { /* "你".repeat(32_001) */ }
    @Test void model_output_over_64k_rejected() { }
    @Test void phone_redacted() {
        var r = Guardrails.redactPii("联系 13800138000 确认");
        assertThat(r.text()).isEqualTo("联系 [手机号] 确认");
        assertThat(r.counts()).containsEntry("phone", 1);
    }
    @Test void email_and_order_id_redacted() { /* a@b.com → [邮箱];ORD-1234567(大小写混合)→ [订单号] */ }
    @Test void order_id_unicode_word_boundary() {
        // 前接中文字符("订单ORD-1234567")在 Python \w(含中文)下不成边界 → 不脱敏;
        // 断言该行为,防止 Java 默认 ASCII \b 漂移
    }
    @Test void grounded_number_passes() {
        assertThat(Guardrails.unsupportedNumericClaims(
            "共 3 笔", List.of(Map.of("count", 3)))).isEmpty();
    }
    @Test void unsupported_number_reported() {
        assertThat(Guardrails.unsupportedNumericClaims("花了 999 元", List.of())).containsExactly(999.0);
    }
    @Test void list_numbering_not_a_claim() { /* "1. 内容" 不算数字声明 */ }
    @Test void sample_labels_not_a_claim() { /* "样本 3:xxx" 规整后不算 */ }
    @Test void rounding_to_6_digits() { /* 1.2345678 → 1.234568(HALF_EVEN,对齐 Python round) */ }
}
```

- [ ] **Step 5.2** 跑红 → 实现要点:PII 三模式(手机号 `(?<!\d)1[3-9]\d{9}(?!\d)`、email、订单号 `\b(?:ORD|ORDER|NO)[-_]?[A-Za-z0-9-]{5,}\b` + CASE_INSENSITIVE + **UNICODE_CHARACTER_CLASS**);`_NUMBER = (?<![A-Za-z0-9_])[-+]?\d+(?:\.\d+)?`;证据序列化非字符串对象先 `Json.write`;两条规整正则(列表编号 `(?m)^\s*\d+[.)、]\s*` 删除;样本标签 → `$1：`);round 用 `BigDecimal.setScale(6, RoundingMode.HALF_EVEN)`
- [ ] **Step 5.3** 跑绿 → Commit:`feat(runtime): 输入输出护栏(PII 脱敏与数字 grounding)`

---

### Task 6: 策略与工具注册表

**Files:**
- Create: `agent-runtime/.../policy/RiskLevel.java`、`ToolPolicy.java`、`PolicyVerdict.java`(sealed + Allowed/ApprovalRequired)、`PolicyGateway.java`、`PolicyException.java`
- Create: `agent-runtime/.../tool/ToolHandler.java`、`ToolDefinition.java`、`ToolRegistry.java`、`ToolException.java`
- Test: `.../policy/PolicyGatewayTest.java`、`.../tool/ToolRegistryTest.java`

**Interfaces(引擎依赖):**
- `enum RiskLevel { READ("read"), LOW_WRITE("low_write"), HIGH_WRITE("high_write"), FORBIDDEN("forbidden") }`(@JsonValue)
- `record ToolPolicy(RiskLevel riskLevel, boolean requiresApproval, String reason)`:compact ctor 中 FORBIDDEN 强制 `requiresApproval=true`;静态 `read()` = `(READ, false, "Read-only operation")`
- `PolicyGateway.enforce(ToolPolicy, String toolName)`:READ/LOW_WRITE → `Allowed.INSTANCE`;HIGH_WRITE 或 requiresApproval → `ApprovalRequired.INSTANCE`;FORBIDDEN 抛 `PolicyException("tool is forbidden by policy: " + name)`
- `record ToolDefinition(String name, String description, Map<String,Object> parameters, ToolPolicy policy, ToolHandler handler, Function<Object,String> resultFormatter)`;`Map<String,Object> schema()` = {name, description, parameters}(**不含 policy — 模型可见面契约**);静态工厂省略 policy/formatter 默认
- `ToolRegistry`:`register`(重名 `IllegalArgumentException("tool already registered: " + name)`)、`names()`、`get(String)`(未知抛 `ToolException("unknown tool: " + name)`)、`List<Map<String,Object>> schemas(Collection<String> allowed)`、`Object execute(String name, Map<String,Object> args, Collection<String> allowed)`(不在 allowed → `ToolException("tool is not enabled for this agent: " + name)`;校验后执行;业务异常包装为 `ToolException(name + " failed: " + msg)`)、`String formatResult(String name, Object result)`

- [ ] **Step 6.1** 失败测试:enforce 三分支 + FORBIDDEN 强制审批 + 异常消息;注册表重名/未知/未启用;schema 校验矩阵(required 缺失、additionalProperties=false 多余参数、string/number/integer/boolean/array/object 类型不符、**boolean 不算 number/integer**、`5.0` 不算 integer、enum、minimum);`schemas()` 不含 policy 键
- [ ] **Step 6.2** 跑红 → 实现(校验对齐 PY `tools._validate`:integer 接受 Byte/Short/Integer/Long/BigInteger,number 接受一切 Number;Boolean 天然被拒)
- [ ] **Step 6.3** 跑绿 → Commit:`feat(runtime): 策略门禁与工具注册表(schema 校验)`

---

### Task 7: 技能路由

**Files:**
- Create: `agent-runtime/.../skill/SkillException.java`、`Trigger.java`(sealed: Word/AllWords)、`SkillMetadata.java`、`SkillCompletionRule.java`、`SkillRoute.java`、`SkillActivation.java`、`SkillSource.java`(接口)、`DirectorySkillSource.java`、`SkillRuntime.java`
- Create(资产拷贝):`agent-runtime/src/test/resources/skills/` ← PY `skills/`(4×SKILL.md + routes.json 原样)
- Test: `.../skill/SkillRuntimeTest.java`

**Interfaces:**
- `interface SkillSource { List<SkillMetadata> list(); String read(String name) throws IOException; }`;`DirectorySkillSource(Path root)` 实现(glob `*/SKILL.md`)
- `SkillRuntime`:`ctor(SkillSource, int maxActive=2, int maxSkillBytes=256_000)`、`ctor(Path root)`、`refresh()`、`List<SkillActivation> activate(String userInput)`、`static List<String> allowedTools(List<SkillActivation>, List<String> fallback)`
- `SkillActivation(name, description, instructions, version, reason, score, allowedTools, requiredTools, requiredToolGroups, requiredToolPlan, outputContract)` — 全 List/Map 不可变

- [ ] **Step 7.1** 拷贝资产:`cp -r "D:/shixi/aicoding/feedback-agent-runtime/skills/"* agent-runtime/src/test/resources/skills/`
- [ ] **Step 7.2** 失败测试(用真实 4 技能资产,`Path.of(getClass().getResource("/skills").toURI())`):

```java
class SkillRuntimeTest {
    SkillRuntime runtime = new SkillRuntime(TEST_SKILLS_ROOT);
    @Test void default_skill_when_no_trigger() {
        var a = runtime.activate("你好");
        assertThat(a).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("bill-triage");
            assertThat(s.reason()).isEqualTo("default");
        });
    }
    @Test void explicit_reference_score_10000() { /* "$monthly-guard-report" */ }
    @Test void unknown_explicit_throws() { }
    @Test void trigger_scoring_prefers_longer_and_priority() { /* 异常类关键词 → anomaly-investigation */ }
    @Test void max_active_two() { /* 同时命中多技能只取 2,按 (-score, name) 排序 */ }
    @Test void version_is_sha256_prefix12() { /* 与手工 sha256 对比 */ }
    @Test void required_tool_groups_resolved() { /* 触发完成规则的请求 → requiredToolPlan 含备选组 */ }
    @Test void allowed_tools_intersect_fallback() { }
    @Test void empty_intersection_throws() { }
    @Test void routes_validation_errors() { /* 写临时目录构造坏 routes.json:缺 routes 数组/未装技能/坏触发词/优先级越界/completion 规则引用未授权工具/output_contract 空章节 → 各自 SkillError 消息 */ }
    @Test void skill_body_limits() { /* 构造 >8000 字符 SKILL.md → SkillError */ }
    @Test void frontmatter_name_mismatch_throws() { }
}
```

- [ ] **Step 7.3** 跑红 → 实现要点(对齐 PY `skills.py`):名称 `^[a-z0-9]+(?:-[a-z0-9]+)*$` ≤64;目录名==name;description ≤1024;显式引用 `\$(name)`=10000 分 `explicit:$name`;触发词得分 `100+priority+max(触发词长)`(长度按 code points;数组触发词=共现全词命中,长度=词长和,reason `a+b`);无命中回落 default(1,"default");frontmatter 标量解析(首行 `---`,遇 `---` 止,跳空行/#,含 `:` 才合法,成对引号剥除);body ≤500 行、≤8000 chars、≤maxSkillBytes;version=sha256(raw)[0:12] hex;激活时匹配 completion 规则(trigger 对 lowered input)生成 requiredTools/groups/plan(保序去重);大小写折叠 `toLowerCase(Locale.ROOT)`(routes.json 词表为中文/ASCII,与 casefold 等价 — 注释说明)
- [ ] **Step 7.4** 跑绿 → Commit:`feat(runtime): 技能路由(SKILL.md 发现/触发词评分/完成契约)`

---

### Task 8: 请求契约编译

**Files:**
- Create: `agent-runtime/.../contract/ArgumentConstraint.java`、`OutputSection.java`、`RequestContract.java`、`RequestContracts.java`(编译器+目录)
- Test: `.../contract/RequestContractsTest.java`

**Interfaces:**
- `RequestContract(List<ArgumentConstraint>, List<OutputSection>)` + `List<String> toolViolations(String toolName, Map<String,Object> arguments, Map<String,Object> schema)` + `List<String> missingSections(String answer)` + `String promptText()`
- `RequestContracts.compile(String userInput, List<SkillActivation> activeSkills)` + `static toolRole(String toolName)` + `outputSectionsMissing(String, List<String>)`

- [ ] **Step 8.1** 失败测试(正则与消息逐字):

```java
class RequestContractsTest {
    @Test void max_rows_compiles_limit_constraint() {
        var c = RequestContracts.compile("搜索一下,最多返回 5 条样本", List.of());
        assertThat(c.argumentConstraints()).singleElement()
            .satisfies(x -> { assertThat(x.toolRoles()).containsExactly("samples");
                             assertThat(x.value()).isEqualTo(5); });
    }
    @Test void search_context_defaults_to_query_role() { }
    @Test void top_items_constraint_targets_anomalies() { /* "前 3 项" → anomalies lte 3 */ }
    @Test void strictest_bound_wins() { /* 同角色多次出现取最小 */ }
    @Test void violation_when_limit_missing_or_too_large_or_non_numeric() {
        // 三条消息逐字:"参数 limit 必须显式提供，且不能超过 5" / "参数 limit 必须是数字" / "参数 limit=7 超过用户要求的最大值 5"
    }
    @Test void tool_role_aliases() {
        assertThat(RequestContracts.toolRole("bill.get_samples")).isEqualTo("samples");
        assertThat(RequestContracts.toolRole("feedback_search")).isEqualTo("query");
        assertThat(RequestContracts.toolRole("work-items.commit_issue")).isEqualTo("commit_issue");
    }
    @Test void sections_from_skill_output_contract_gated_by_terms() { /* 月报 gate_terms 命中 → 四章 */ }
    @Test void missing_sections_detects_markdown_and_json() { /* "## 执行摘要" / {"执行摘要":1} 均算有;JSON 键大小写不敏感 */ }
    @Test void prompt_text_renders() { /* "动态参数契约：query.limit <= 5（必须显式传参）。" 样式 */ }
}
```

- [ ] **Step 8.2** 跑红 → 实现(正则逐字:`最多\s*(?:返回|读取|给出)?\s*(\d+)\s*条([^，。；,;]{0,10})`、`(?:返回|给出|列出)?\s*前\s*(\d+)\s*项`;前缀窗口 `max(0,start-24)`;tail 含"样本"或 prefix 末 8 字符含"样本"→samples,prefix 含 搜索/检索/查找→query,否则 query;章节目录 10 项标签逐字拷贝;`_has_section` Markdown 行首模式 `(?im)^\s*(?:#{1,6}\s*|[-*]\s*|\*\*)?label(?:\*\*)?\s*(?::|：|$)`,JSON 键 `toLowerCase(Locale.ROOT)`)
- [ ] **Step 8.3** 跑绿 → Commit:`feat(runtime): 用户原话动态契约(limit 约束/章节门禁)`

---

### Task 9: 上下文构建器

**Files:**
- Create: `agent-runtime/.../engine/ContextBuilder.java`(含 `PROTOCOL` 常量)、`AgentSpec.java`(record + Builder)
- Test: `.../engine/ContextBuilderTest.java`、`AgentSpecTest.java`

**Interfaces:**
- `record AgentSpec(String name, String instructions, List<String> toolNames, int maxSteps, Duration runTimeout, int summaryThreshold, int summaryKeepRecent, int toolResultContextLimit, int maxContextChars)` — Builder 提供默认值(maxSteps=8, runTimeout=null, 40/12/1500/80_000);compact ctor 校验消息逐字
- `List<ChatMessage> ContextBuilder.build(AgentSpec, String summary, List<ChatMessage> messages, List<SkillActivation>, RequestContract)`

- [ ] **Step 9.1** 失败测试:PROTOCOL 头消息(`你是 {name}。\n{instructions}\n\n{PROTOCOL}`);技能块(`## {name}（版本 {version}）` + 不可信声明前言逐字);required tools 消息;契约消息;`较早会话摘要：\n{summary}`;预算兜底(超预算时从旧到新丢弃,最新一条永远保留,`maxContextChars=0` 不限)
- [ ] **Step 9.2** 跑红 → 实现(中文文案从 PY `context.py` 逐字拷贝;长度用 `Strings.len`)
- [ ] **Step 9.3** 跑绿 → Commit:`feat(runtime): 上下文构建器(协议/技能块/字符预算)`

---

### Task 10: 端口与测试替身

**Files:**
- Create: `agent-runtime/.../store/ConversationStore.java`、`ApprovalRecord.java`、`ApprovalStore.java`、`TraceWriter.java`
- Create: `agent-runtime/.../llm/LlmClient.java`、`LlmRequest.java`、`LlmResult.java`
- Create: `agent-runtime/.../testing/InMemoryConversationStore.java`、`InMemoryApprovalStore.java`、`InMemoryTraceWriter.java`、`ScriptedLlm.java`、`FinalLlm.java`
- Test: `.../testing/InMemoryApprovalStoreTest.java`、`ScriptedLlmTest.java`

**Interfaces(引擎与 PG 仓储共同依赖):**
- `interface ConversationStore { Conversation load(String sessionId); void save(Conversation c); }`
- `record ApprovalRecord(String id, String sessionId, String traceId, int step, String toolName, Map<String,Object> arguments, String riskLevel, String reason, String status, Map<String,Object> checkpoint, String requestedAt, String decidedAt, String decidedBy, String decisionNote, String executedAt, String executionError)` + `Map<String,Object> asMap(boolean includeCheckpoint)`(字段名 = PY `ApprovalRequest.as_dict`)
- `interface ApprovalStore { ApprovalRecord request(...); ApprovalRecord get(String); List<ApprovalRecord> list(String sessionId, List<String> statuses, int limit); ApprovalRecord decide(String id, boolean approved, String decidedBy, String note); ApprovalRecord markExecution(String id, boolean succeeded, String error); int deleteForConversation(String sessionId); }`(各错误消息对齐 PY `policy.py`:如 `approval is already decided: {id}`、`only approved requests can be executed: {status}`;id 形如 `POL-` + hex12 大写)
- `interface TraceWriter { void appendEvent(String sessionId, String traceId, String agent, Map<String,Object> record); }`
- `interface LlmClient { LlmResult complete(LlmRequest request); }`;`record LlmRequest(List<ChatMessage> messages, List<Map<String,Object>> toolSchemas)`;`record LlmResult(String raw, Map<String,Object> usage, String model)`
- 替身:`ScriptedLlm(Object... script)`(String 原样 / Map→`Json.write`;每次 complete 弹出;耗尽抛 `AssertionError("script exhausted")`;usage=`Map.of()` model=`"scripted"`)、`FinalLlm(String answer)`;`InMemoryApprovalStore`(decide 双状态检查 + 已决拒绝)、`InMemoryTraceWriter`(`List<Map> records()` / `records(String sessionId, String traceId)`)

- [ ] **Step 10.1** 失败测试:decide 状态机(pending→approved/rejected→executed/failed;非 pending 二次 decide 抛异常消息);markExecution 仅 approved 可执行;asMap 字段名与 includeCheckpoint 行为;ScriptedLlm 弹尽即 AssertionError、Map 自动序列化
- [ ] **Step 10.2** 跑红 → 实现 → 跑绿
- [ ] **Step 10.3** Commit:`feat(runtime): 存储端口、LLM 边界与确定性替身`

---

### Task 11: AgentRuntime · 核心循环与 final 门禁

**Files:**
- Create: `agent-runtime/.../engine/AgentRuntime.java`(本任务实现 run 主路径 + final 三连门禁 + maxSteps)
- Test: `.../engine/AgentRuntimeTest.java`

**Interfaces:**
- `AgentRuntime.Builder(spec, llm, tools, conversations, traceWriter)` 可选 `skills/policyApprovalStore(启用审批)/hooks/contextBuilder`;`build()` 时校验 `AgentSpec references unregistered tools: ...`(排序逗号连接)
- `AgentResponse run(String sessionId, String userInput)` / `resume(String approvalId)` / `finalizeRejection(String approvalId)`

- [ ] **Step 11.1** 失败测试:

```java
class AgentRuntimeTest {
    // 公共夹具:InMemory 三件套 + FinalLlm/ScriptedLlm + demo 工具(read: demo.lookup / high_write: demo.write)
    @Test void direct_final_completes() {
        var r = runtime.run("s1", "你好");
        assertThat(r.status()).isEqualTo("completed");
        assertThat(r.answer()).isEqualTo("你好,共 1 笔");
        assertThat(r.steps()).isEqualTo(1);
    }
    @Test void tool_then_final_loop() { /* Scripted: [tool_call demo.lookup, final] → steps=2, decorate 含"本轮实际执行结果" */ }
    @Test void empty_input_rejected() { /* IllegalArgumentException("user input cannot be empty") */ }
    @Test void unregistered_spec_tool_rejected_at_build() { }
    @Test void unknown_tool_name_becomes_tool_error_then_continue() { /* Scripted: [call 不存在工具, final] → completed */ }
    @Test void final_blocked_by_missing_required_tools() { /* 技能激活 requiredTools 未完成 → 注入 system 消息继续,最终完成 */ }
    @Test void final_blocked_by_missing_sections() { /* 月报契约章节缺失 → 继续循环 */ }
    @Test void final_blocked_by_unsupported_numbers() { /* final 含无证据数字 → 注入后修正 */ }
    @Test void pii_redacted_in_final() { /* final 含手机号 → 输出 [手机号],事件 output_redacted */ }
    @Test void max_steps_aborts_with_message() { /* FinalLlm 恒返非法 JSON? 用 Scripted 全 tool_call → 8 步后 failed,answer 含"已达到最大执行步数（8）" */ }
    @Test void malformed_model_output_fails_run() { /* Scripted: ["不是JSON"] → status=failed, answer 含"Agent 执行模型步骤失败" */ }
    @Test void tool_result_truncated_in_context() { /* toolResultContextLimit=10 → 工具消息含"已截断至 10 字符" */ }
    @Test void argument_contract_blocks_tool() { /* "最多 1 条" 输入 + limit=5 调用 → argument_blocked 事件,工具未执行 */ }
}
```

- [ ] **Step 11.2** 跑红 → 实现要点(对齐 PY `engine.py` 逐段):
  - `run`:strip→空抛 IllegalArgumentException;`validateUserInput`;load;`summaryThreshold>0 && messages.size()>threshold` → `compressHistory` + save + `history_compressed`(probe traceId);append user;traceId=hex32;`run_start(input, agent)`;activate(SkillException → answer=`Skill 路由或加载失败：{msg}` status=failed steps=0);contract;build;`loop(...)`
  - `loop`:`for step in startStep..maxSteps`;超时检查(`System.nanoTime()`,文案 `已达到最大执行时间（{g} 秒），任务被 Harness 安全停止。`,`gFormat` 去尾零);model_start → llm.complete → `validateModelOutput`(违例先 emit `model_output_blocked(reason=length_limit)` 再抛)→ `model_output`(raw 截 20_000/latency_ms/usage/model)→ parse → `model_decision`(thought/tool/final 布尔);**任何异常** → `run_error` + failed(答案 `Agent 执行模型步骤失败:{exc}`)
  - final 三连:missingRequiredTools(游标序匹配,对齐 `_missing_required_tools`)→ `completion_blocked` + system 消息(逐字);missingSections → `output_contract_blocked`;redact → `output_redacted`;grounding 证据 = fullPayloads + working 中 assistant&&toolCallId!=null 的 content → `grounding_blocked` + 消息;全过 → decorate → finish(再次 redact + append assistant + save + `run_end(answer,status)`)
  - tool 分支:callId=hex12;未知工具 → 工具错误消息对(见下)continue;`toolViolations`(取 schema.parameters)非空 → `argument_blocked` + system 消息;`PolicyGateway.enforce`(PolicyException → 工具错误对 continue;ApprovalRequired → pause,Task 12);执行:`tool_start` → registry.execute → payload=`Json.write`(非 ASCII 直出)→ summary/formatResult → storage_path 记 artifact → `tool_end(result, latency)`;**degraded 判定**:result 是 Map 且 `degraded==true` 且含 `error` 且 riskLevel==HIGH_WRITE → `tool_error` + 记失败(executed=False);否则成功 + fullPayloads.add;ToolException → payload={"error":msg} + `tool_error`;工具消息对 = assistant(`{"tool_call":{"name":..,"arguments":..}}` compact,name=tool,callId)+ tool(payload 经 `contextualPayload` 截断);working 与 conversation 同时追加
  - 循环耗尽:`max_steps` 事件 + failed(文案 `已达到最大执行步数（{N}），任务被 Harness 安全停止。`)
  - `emit(type, traceId, sessionId, step, kv...)` → `RunEvent.of`;hooks 逐个 try-catch 吞没(**Observability must never break the Agent loop** 注释保留);末位 hook = trace:`{timestamp, event, trace_id, step, agent, **data}` → traceWriter.appendEvent
- [ ] **Step 11.3** 跑绿 → Commit:`feat(runtime): 引擎核心循环与 final 三连门禁`

---

### Task 12: AgentRuntime · 审批暂停/恢复/拒绝

**Files:**
- Modify: `agent-runtime/.../engine/AgentRuntime.java`(补 pause/resume/finalizeRejection)
- Test: `.../engine/AgentApprovalFlowTest.java`

- [ ] **Step 12.1** 失败测试:

```java
class AgentApprovalFlowTest {
    @Test void high_write_pauses_with_checkpoint_v2() {
        var r = runtime.run("s1", "帮我写入");   // Scripted: [tool_call demo.write]
        assertThat(r.status()).isEqualTo("approval_pending");
        assertThat(r.approval()).containsEntry("tool_name", "demo.write")
            .containsEntry("status", "pending");
        var cp = store.get((String) r.approval().get("id")).checkpoint();
        assertThat(cp.get("schema_version")).isEqualTo(2);
        assertThat(cp).containsKeys("full_tool_payloads","session_id","trace_id","user_input",
            "step","call_id","skill_versions","allowed_tools","execution_summaries",
            "artifact_paths","completed_tools");
    }
    @Test void resume_after_approval_executes_and_completes() {
        // run→pause; decide(true,"admin"); ScriptedLlm 第二段 final
        // 断言:status=completed;approval 最终 executed(markExecution);completed_tools 含 demo.write;答案 decorate
    }
    @Test void resume_rejects_when_not_approved() { /* PolicyException("approval must be approved before resume: pending") */ }
    @Test void resume_detects_skill_version_drift() { /* pause 后换 SkillRuntime 根目录改 SKILL.md → PolicyException("active Skill versions changed...") */ }
    @Test void resume_detects_tool_policy_drift() { /* checkpoint allowed_tools 与当前不一致 */ }
    @Test void finalize_rejection_answers_honestly() {
        // decide(false, "admin", "不需要") → finalizeRejection
        // status=rejected;answer 含 "已拒绝高风险操作 `demo.write`，Agent 未执行该工具。" 与 "审批说明：不需要"
    }
    @Test void degraded_high_write_counts_as_failure() {
        // high_write 工具 handler 返回 {"degraded":true,"error":"circuit open"} → resume 后 approval=failed 非 executed
    }
    @Test void resume_restores_completed_tools_for_final_gate() { }
}
```

- [ ] **Step 12.2** 跑红 → 实现(对齐 PY:pause 文案 `操作 \`{tool}\` 需要人工审批，Agent 已保存 Checkpoint 并暂停。\n审批通过后会从当前步骤继续，不会重复前面的查询。`;resume 六重校验顺序:schema_version==2 → session 匹配 → activate(不发事件) → skill_versions 全等 → allowed_tools 全等(有序)→ 工具仍在 allowed;`run_resume` 事件(approval_id/decided_by);executeTool(step=approval.step)→ markExecution → 成功才补 completed_tools → loop(step+1, 恢复 fullPayloads);finalizeRejection 文案见测试)
- [ ] **Step 12.3** 跑绿 → Commit:`feat(runtime): 三阶段审批暂停/恢复/诚实拒绝`

---

### Task 13: AgentRuntime · 压缩/超时/事件序列

**Files:**
- Modify: `agent-runtime/.../engine/AgentRuntime.java`(补 compressHistory/gFormat)
- Test: `.../engine/AgentCompressTest.java`、`AgentEventSequenceTest.java`

- [ ] **Step 13.1** 失败测试:
  - `compress_history_summarizes_qa_pairs`:>keep_recent 旧消息折叠为 `- 问:.. 答:..`(问题截 120、答截 200,`{final:...}` JSON 解包);连续未答问题留痕 `(该轮无最终回答)`;已有 summary 的 `- 问:` 行保留并拼接,尾部 maxPairs=20;**返回新对象,入参不变**
  - `compression_triggered_at_threshold`:threshold=4,预置 6 条 → run 时事件 `history_compressed` 携带 before/after
  - `run_timeout_aborts`:runTimeout=50ms + ScriptedLlm 每次 complete 睡 80ms、剧本两条 → failed,answer 含 `已达到最大执行时间`
  - `event_sequence_for_full_run`:InMemoryTraceWriter 断言序列 `run_start → (skill_activated) → model_start → model_output → model_decision → tool_start → tool_end → model_start → model_output → model_decision → run_end`
  - `resume_requires_gateway`:无审批仓储构造的 runtime.resume → PolicyException("this Agent has no Policy Gateway")
- [ ] **Step 13.2** 跑红 → 实现 → 跑绿
- [ ] **Step 13.3** Commit:`feat(runtime): 历史压缩、超时止损与事件序列`

---

### Task 14: PG 仓储

**Files:**
- Create: `billguard-web/src/main/java/io/github/lxyang01/billguard/storage/PgJson.java`、`PgConversationStore.java`、`PgApprovalStore.java`、`PgTraceWriter.java`
- Test: `billguard-web/src/test/java/io/github/lxyang01/billguard/storage/PgStoresTest.java`

**Interfaces:** 与 runtime 端口同名同签名(PgConversationStore/PgApprovalStore 实现 ApprovalStore、PgTraceWriter 实现 TraceWriter;构造注入 `JdbcTemplate`)

- [ ] **Step 14.1** 失败测试(继承 PgTestBase):

```java
class PgStoresTest extends PgTestBase {
    @Test void conversation_roundtrip_and_upsert() { /* save→load 字段全等;再 save 覆盖 messages;不存在 id → 空会话 */ }
    @Test void invalid_session_id_rejected() { /* 空串 / 201 字符 → IllegalArgumentException("invalid session id") */ }
    @Test void approval_lifecycle_in_pg() { /* request→get→decide→markExecution 全字段回读;arguments/checkpoint JSONB round-trip */ }
    @Test void concurrent_decide_exactly_once() throws Exception {
        var store = new PgApprovalStore(jdbc);
        var a = store.request(...);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        var results = pool.invokeAll(List.of(
            call(() -> { barrier.await(); return store.decide(a.id(), true, "alice", ""); }),
            call(() -> { barrier.await(); return store.decide(a.id(), false, "bob", ""); })));
        // 恰好一个成功,另一个抛 PolicyException("approval is already decided: ...")
        // 最终状态二选一,decided_by 与胜者一致 —— PG 行锁下的恰好一次
    }
    @Test void mark_execution_writes_execution_result() { /* execution_result JSONB {succeeded, executed_at} */ }
    @Test void trace_appends_events_same_trace() { /* append ×3 → 单行 events 数组长度 3,含 agent */ }
    @Test void delete_for_conversation() { }
}
```

- [ ] **Step 14.2** 跑红 → 实现要点:
  - SQL 逐字对齐 PY `storage_pg.py`(占位符 `%s`→`?`):`PgConversationStore.save` = `INSERT INTO sessions(session_id, owner, summary, messages, updated_at) VALUES (?,?,?,?,?) ON CONFLICT (session_id) DO UPDATE SET owner=EXCLUDED.owner, summary=EXCLUDED.summary, messages=EXCLUDED.messages, updated_at=EXCLUDED.updated_at`;messages 列 Jackson ↔ `List<ChatMessage>`
  - `PgApprovalStore.decide`:先 `SELECT status`(非 pending 抛 `approval is already {status}`)→ 条件 `UPDATE ... WHERE id=? AND status='pending'`,**updateCount==0 即抛 `approval is already decided`** —— 不得"优化"为先查后写(并发窗口正是条件 UPDATE 关掉的,风险清单 §12)
  - `PgTraceWriter.appendEvent`:`TransactionTemplate` 内 `SELECT events ... FOR UPDATE` → append → upsert(对齐 PY 读-改-写)
  - `PgJson.value(Object/JsonNode)` → `PGobject(type="jsonb")`
- [ ] **Step 14.3** 跑绿 → Commit:`feat(web): PG 会话/审批/追踪仓储(条件 UPDATE 恰好一次)`

---

### Task 15: Redis 协调

**Files:**
- Create: `billguard-web/src/main/java/io/github/lxyang01/billguard/coordination/RedisSessionLock.java`、`RedisLlmLimiter.java`
- Test: `billguard-web/src/test/java/io/github/lxyang01/billguard/coordination/RedisCoordinationTest.java`

**Interfaces:**
- `RedisSessionLock(RedisCommands<String,String> commands, String sessionId, Duration ttl)`:`boolean acquire()`(SET NX PX,键 `lock:session:{sha256hex(sessionId)}`,value=随机 holder hex32)、`void release()`(Lua 持有者校验)、`static Optional<RedisSessionLock> acquire(commands, sessionId, ttl)`
- `RedisLlmLimiter(commands, int limit, String key="llm:slots")`:`boolean acquire()`(INCR Lua check-and-incr + EXPIRE 120s)、`void release()`(带地板 DECR Lua)

- [ ] **Step 15.1** 失败测试(继承 PgTestBase 取 redisUri(),Lettuce `RedisClient.create(uri).connect()`):

```java
class RedisCoordinationTest extends PgTestBase {
    @Test void lock_acquire_and_release_by_holder() { /* acquire→二次 acquire 失败→release 后可再取 */ }
    @Test void release_only_own_lock() { /* A 持锁过期后 B 取锁,A.release 不误删 B 的锁 */ }
    @Test void limiter_enforces_limit_and_floors() {
        // limit=2:两次 true,第三次 false;release 两次后再 true;release 到底不为负
    }
}
```

- [ ] **Step 15.2** 跑红 → 实现(Lua 脚本与键名逐字拷贝 PY `coordination.py`;sha256 用 `MessageDigest.getInstance("SHA-256")` hex 小写)
- [ ] **Step 15.3** 跑绿 → Commit:`feat(web): Redis 会话锁与 LLM 槽位限流(Lua 原子)`

---

### Task 16: M1 装配与端到端

**Files:**
- Modify: `billguard-web/src/main/resources/application.yml`(移除 Task 1 的自动配置排除,启用 DataSource/Flyway/Redis;`BILLGUARD_PG_DSN`/`BILLGUARD_REDIS_URL` 环境变量,DSN 缺省 `postgresql://billguard:billguard@localhost:5432/billguard`)
- Create: `billguard-web/src/main/java/io/github/lxyang01/billguard/config/RuntimeConfig.java`(装配 DataSource/JdbcTemplate/RedisClient commands/三个 PG 仓储 Bean)
- Create: `billguard-web/src/main/resources/skills/` ← 资产拷贝(供后续里程碑,M1 打包完整)
- Test: `billguard-web/src/test/java/io/github/lxyang01/billguard/M1EndToEndTest.java`

- [ ] **Step 16.1** 失败测试(@SpringBootTest + @ServiceConnection PostgreSQLContainer + Redis GenericContainer @DynamicPropertySource;`@Testcontainers(disabledWithoutDocker = true)`):

```java
class M1EndToEndTest {
    @Test void approval_pause_resume_full_path() {
        // 真实 PG 仓储 + InMemory skills(空,不激活)+ ScriptedLlm:[tool_call demo.write, final "已完成"]
        var agent = assembledRuntime();
        var first = agent.run("e2e-1", "执行写入");
        assertThat(first.status()).isEqualTo("approval_pending");
        var id = (String) first.approval().get("id");
        assertThat(approvals.decide(id, true, "admin", "同意")).isNotNull();
        var done = agent.resume(id);
        assertThat(done.status()).isEqualTo("completed");
        assertThat(approvals.get(id).status()).isEqualTo("executed");
        // 会话持久化:PG sessions 表 messages 末条 assistant 且含 "已完成"
        // 追踪持久化:PG traces 表该 trace_id 的 events 数组 ≥ 8
    }
    @Test void session_lock_held_during_run() {
        // RedisSessionLock.acquire 同 id 第二次为空(锁语义联通)
    }
}
```

- [ ] **Step 16.2** 跑红 → 装配实现 + 移除排除项;`mvn -q verify` 全模块绿
- [ ] **Step 16.3** Commit:`feat(web): M1 装配与端到端审批恢复路径(真实 PG/Redis)`

---

## M1 完成定义

1. `mvn -q verify` 全绿(Windows Docker Desktop 运行中,Testcontainers 可用)
2. 端到端测试证明:审批暂停 → decide → resume → executed → completed 全链路走真实 PG;锁语义走真实 Redis
3. runtime 模块 `mvn -q dependency:tree` 无任何 `org.springframework` 依赖
4. 探针级对齐自查:对照 PY `tests/test_agent.py`、`test_guardrails.py`、`test_contracts.py` 的断言点,确认本计划测试已覆盖等价行为

## Self-Review 记录

- 规格覆盖:spec §3(模块)、§5(类型/引擎/状态机 0-1)、§6(仅 health + 移交 M2 的注记)、§7(Flyway/JdbcTemplate/三仓储)、§2 原则(契约清单逐字);MCP/熔断/工单状态机 → M3;25 探针 → M4
- 占位符扫描:无 TBD;所有中文文案/SQL/Lua 标注"逐字"来源文件
- 类型一致性:ChatMessage/Conversation/AgentResponse/RunEvent/ApprovalRecord/LlmClient 签名在 Task 3/10 定义,Task 11-16 使用一致
