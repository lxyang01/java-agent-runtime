# BillGuard Java Agent Runtime

[![CI](https://github.com/lxyang01/java-agent-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/lxyang01/java-agent-runtime/actions/workflows/ci.yml)

可审计账单守卫 Agent 的 **Java 工程化实现**。项目定位:一个通用的可审计 **Agent Runtime**(引擎/策略门禁/护栏/技能路由/三阶段审批状态机),BillGuard 账单域是跑在其上的第一个业务载体。

> 设计规格:`docs/superpowers/specs/2026-09-22-java-agent-runtime-design.md`
> 开发手册:`docs/development.md`
> 行为参照:Python 版(同一作者的 agent_harness distributed 分支)—— 与其**行为契约等价**(安全与审计语义,25 条对抗探针双语全绿),实现为 Java 原生。

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

## 核心防线(与 Python 版逐条等价,**25/25 对抗探针全绿**)

- 模型只能调用注册过的工具;金额与结论必须有工具证据(数字 grounding 门禁)
- 高风险写操作走三阶段审批:准备(存 checkpoint v2)→ 人工批准(条件 UPDATE,并发恰好一次)→ 提交(resume 校验技能版本/工具白名单无漂移)
- final 三连门禁:必做工具 → 输出章节契约 → PII 脱敏 + 数字 grounding
- 用户原话编译的动态参数契约(`最多 N 条` → limit ≤ N,程序性拦截)
- MCP owner 身份注入(schema 隐藏 + 参数白名单 + 强制覆盖,模型不可见不可伪造)
- 熔断状态机:重连退避 1s/2s/4s ×3 → OPEN 60s → 半开单探;降级结果结构化
- 登录节流(用户名,IP 固定窗口)、同源 CSRF、能力门禁(16 条路径)

## 里程碑(全部完成)

- [x] **M1 Runtime 内核** —— agent-runtime 全量(149 测试)+ PG/Redis 仓储 + 端到端审批恢复(Testcontainers)
- [x] **M2 Web 全量** —— 安全三件套/30 端点/账单域/编排/Micrometer 指标
- [x] **M3 MCP 双端** —— 双 MCP server + 熔断客户端 + owner 注入 + 双闸审批
- [x] **M4 证明体系** —— 25 条对抗探针全量平移并通过(adv-001..025)
- [x] **M5 生产化** —— compose 集群(nginx + web×2 + PG + Redis + 2×MCP)+ CI + 运维文档

**295 项测试全绿**;`docker compose up --build -d` 一条命令拉起全集群。

## 技术栈

| 层 | 技术 |
|---|---|
| 运行时内核 | Java 21(sealed/record/pattern matching)、Jackson —— 零框架依赖 |
| Web | Spring Boot 3.5(WebMVC/Security/JDBC)、Spring AI(OpenAI 兼容 ChatModel) |
| MCP | 官方 MCP Java SDK 0.18(无状态 streamable-http,server 与 client 同库) |
| 存储 | PostgreSQL(Flyway/JdbcTemplate,条件 UPDATE 恰好一次)+ Redis(Lettuce,Lua 原子) |
| 测试 | JUnit5 + AssertJ + Testcontainers + Mockito + MockMvc 全过滤器链 |
| 部署 | Docker 多阶段(temurin-21 JRE)、docker-compose v2、GitHub Actions |

## 快速开始

```bash
# 环境:JDK 21 + Maven 3.9 +(本仓库约定 ~/tools/javaenv.sh)+ Docker Desktop 运行中
. ~/tools/javaenv.sh
mvn verify                # 全模块:编译 + 单测 + 集成测试(295 项)

# 集群(可选):
docker compose up --build -d
curl -s http://localhost:8080/api/health        # {"ok":true}

# 播种管理员(pgdata 卷保留时只需一次):
docker compose exec -T web-1 java -jar /app/web.jar \
  --users add admin --role admin --password-stdin <<< 'Your-Password-1'
```

真实对话:把 Key 写进 `.env`(`API_KEY=sk-...`,compose 自动读取,已 gitignore);本地访问 `http://localhost:8080`。

## 与 Python 版的关系

Python 版(agent_harness,distributed 分支)是行为规格书,不是翻译源:本项目与其共享 PG schema(V001 SQL 逐字节相同)、Redis 键语义、trace/checkpoint JSON 形状与全部安全文案 —— 并以 **25 条对抗探针双语全绿**证明等价。实现是 Java 原生的:sealed 决策类型、record 契约、Builder 装配、`LlmClient` 值携带 usage(替代 thread-local)、Spring Security 过滤器链、MCP Java SDK。详见 `docs/development.md` §4「契约清单」。
