# Java Agent Runtime · BillGuard

[![CI](https://github.com/lxyang01/java-agent-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/lxyang01/java-agent-runtime/actions/workflows/ci.yml)

一个通用的可审计 **Agent Runtime**(引擎/策略门禁/护栏/技能路由/三阶段审批状态机),BillGuard 账单守卫是跑在其上的业务载体。

> 设计规格:`docs/specs/design.md` · 开发手册:`docs/development.md`

## 模块

```
agent-runtime       纯 Java 库(仅 Jackson,零 Spring 依赖,构建强制)
                    引擎循环 · 决策解析 · 护栏(PII/数字 grounding) · 策略门禁
                    工具注册表 · 技能路由 · 动态契约 · 三阶段审批 · 事件追踪
billguard-domain    业务域库(账单/工单/证据 + 全部 PG 存储 + 认证/协调)
billguard-mcp       两个 MCP server(bill :8010 / work-item :8020,profile 选择)
billguard-web       Spring Boot 3 装配:Security 三件套 · 30 端点 · 编排 · Micrometer
billguard-eval      25 条对抗探针 + 目录/报告(确定性,零真实模型)
```

依赖方向严格单向:`web/mcp → domain → runtime`;runtime 不知道 Spring 的存在(构建期保证)。

## 核心防线(25/25 对抗探针防御率 100%)

- 模型只能调用注册过的工具;金额与结论必须有工具证据(数字 grounding 门禁)
- 高风险写操作走三阶段审批:准备(存 checkpoint v2)→ 人工批准(条件 UPDATE,并发恰好一次)→ 提交(resume 校验技能版本/工具白名单无漂移)
- final 三连门禁:必做工具 → 输出章节契约 → PII 脱敏 + 数字 grounding
- 用户原话编译的动态参数契约(`最多 N 条` → limit ≤ N,程序性拦截)
- MCP owner 身份注入(schema 隐藏 + 参数白名单 + 强制覆盖,模型不可见不可伪造)
- 熔断状态机:重连退避 1s/2s/4s ×3 → OPEN 60s → 半开单探;降级结果结构化
- 登录节流(用户名,IP 固定窗口)、同源 CSRF、能力门禁(16 条路径)

## 技术栈

| 层 | 技术 |
|---|---|
| 运行时内核 | Java 21(sealed/record/pattern matching)、Jackson —— 零框架依赖 |
| Web | Spring Boot 3.5(WebMVC/Security/JDBC)、Spring AI(OpenAI 兼容 ChatModel) |
| MCP | 官方 MCP Java SDK 0.18(无状态 streamable-http,server 与 client 同库) |
| 存储 | PostgreSQL(Flyway/JdbcTemplate,条件 UPDATE 恰好一次)+ Redis(Lettuce,Lua 原子) |
| 测试 | JUnit5 + AssertJ + Testcontainers + Mockito + MockMvc 全过滤器链 |
| 部署 | Docker 多阶段(temurin-21 JRE)、docker-compose v2、GitHub Actions |

## 架构一句话

**引擎层**(纯库):模型输出是不可信输入 —— 决策协议解析 → 策略门禁 → 工具白名单执行 → 输出三连门禁,每步产生可持久化事件。**编排层**:Redis 会话锁(423)+ LLM 槽位(429)+ 证据链落库。**服务层**:无状态 web×2 经 nginx ip_hash,双 MCP server 经熔断客户端访问,owner 身份在注入层强制。

## 快速开始

```bash
mvn verify                # 全模块:编译 + 单测 + 集成测试(计数见 mvn 输出)

# 集群:
docker compose up --build -d
curl -s http://localhost:8080/api/health        # {"ok":true}

# 播种管理员(pgdata 卷保留时只需一次):
docker compose exec -T web-1 sh -c \
  'java -jar /app/web.jar --users=add admin --role=admin --password=Your-Password-1 --server.port=0'
```

真实对话:把 Key 写进 `.env`(`API_KEY=sk-...`,compose 自动读取,已 gitignore);本地访问 `http://localhost:8080`。

## 测试体系

- 分层测试:内核(纯内存,毫秒级)/ 业务域(Testcontainers PG/Redis)/ Web(MockMvc 全过滤器链 + 集成)/ 评测(25 条对抗探针 + 目录校验);计数以 `mvn verify` 输出为准,不在文档里维护影子数字
- **MCP 协议级回环**:真实 HTTP 起 server + 真客户端 initialize/listTools/callTool(BillServerTest、WorkItemServerTest、McpAuthTest,含认证与 owner 终裁);facade 级集成(McpFacadeIntegrationTest)聚焦 web 侧装配,不起协议服务
- **25 条对抗探针**(adv-001..025):提示注入/伪造身份/并发双提交/跨租户窃取/CSRF/暴力破解/路径穿越/资源预算 —— 全部确定性(剧本模型替身,零真实 Key、零网络),失败即运行时控制缺口而非模型分数
- Testcontainers:PG/Redis 随测随起;MockMvc 走真实 Security 过滤器链
