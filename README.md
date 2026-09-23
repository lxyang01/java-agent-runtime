# BillGuard Java Agent Runtime

可审计账单守卫 Agent 的 **Java 工程化实现**。项目定位:一个通用的可审计 **Agent Runtime**(引擎/策略门禁/护栏/技能路由/三阶段审批状态机),BillGuard 账单域是跑在其上的第一个业务载体。

> 设计规格:`docs/superpowers/specs/2026-09-22-java-agent-runtime-design.md`
> 开发手册:`docs/development.md`
> 行为参照:Python 版(D:\shixi\aicoding\feedback-agent-runtime,distributed 分支)—— 本项目与其**行为契约等价**(安全与审计语义),实现为 Java 原生。

## 模块

```
agent-runtime   纯 Java 库(仅 Jackson,零 Spring 依赖,构建强制)
                引擎循环 · 决策解析 · 护栏(PII/数字 grounding) · 策略门禁
                工具注册表 · 技能路由 · 动态契约 · 三阶段审批 · 事件追踪
billguard-web   Spring Boot 3 装配
                PG 仓储(JdbcTemplate/Flyway) · Redis 协调(锁/LLM 槽位)
                Web API · 安全 · 指标(M2+) · MCP(M3+)
```

依赖方向严格单向:`billguard-web → agent-runtime`;runtime 不知道 Spring 的存在。

## 核心防线(与 Python 版逐条等价)

- 模型只能调用注册过的工具;金额与结论必须有工具证据(数字 grounding 门禁)
- 高风险写操作走三阶段审批:准备(存 checkpoint v2)→ 人工批准(条件 UPDATE,并发恰好一次)→ 提交(resume 校验技能版本/工具白名单无漂移)
- final 三连门禁:必做工具 → 输出章节契约 → PII 脱敏 + 数字 grounding
- 用户原话编译的动态参数契约(`最多 N 条` → limit ≤ N,程序性拦截)

## 里程碑

- [x] **M1 Runtime 内核** —— agent-runtime 全量(149 测试)+ PG/Redis 仓储 + 端到端审批恢复(Testcontainers)
- [x] **M2 Web 全量** —— 安全三件套/30 端点/账单域/编排/Micrometer 指标
- [x] **M3 MCP 双端** —— 双 MCP server + 熔断客户端 + owner 注入 + 双闸审批
- [ ] M4 证明体系 —— 25 条对抗探针全量平移并通过
- [ ] M5 生产化 —— compose 集群 + CI + 运维文档

## 快速开始

```bash
# 环境:JDK 21 + Maven 3.9 +(本仓库约定 ~/tools/javaenv.sh)
. ~/tools/javaenv.sh
mvn verify          # 全模块构建 + 测试(需 Docker Desktop 运行,Testcontainers)
```

详见 `docs/development.md`。
