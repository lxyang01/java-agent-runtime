# M5 · 生产化(compose 集群 + CI)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一条命令拉起全集群(nginx + web×2 + PG + Redis + 2×MCP),GitHub Actions CI(`mvn verify` + Testcontainers),运维文档;README 完成求职叙事收尾。

**Architecture:** 拓扑与 Python 版逐项对齐 —— 同一 7 服务拓扑、同一 nginx ip_hash/max_fails/X-Real-IP 语义、同一健康检查策略、同一卷布局;差异仅在镜像(Temurin JRE 多阶段构建,exec jar)与启动方式(profile 选择 MCP server)。

**Tech Stack:** Docker 多阶段构建(temurin-21)、docker-compose v2、GitHub Actions。

## Global Constraints

- 拓扑逐项对齐 Python compose:nginx(:8080,ip_hash + max_fails=2/fail_timeout=10s + proxy_next_upstream error timeout 502 503 + Host=$http_host 透传 + X-Real-IP 覆写)、web-1/web-2(无状态,8001/8002)、postgres:16-alpine(pgdata 卷,宿主机 127.0.0.1:5433)、redis:7-alpine(宿主机 127.0.0.1:6380)、bill-server(:8010)、work-item-server(:8020)
- 环境变量对齐:`BILLGUARD_PG_URL`(JDBC 形态)/`BILLGUARD_REDIS_URL`/`BILLGUARD_BILL_MCP_URL`/`BILLGUARD_WORK_ITEM_MCP_URL`/`BILLGUARD_AUTO_MIGRATE` 不需要(Flyway 启动即迁移)/`API_KEY` 或 `OPENROUTER_API_KEY` 透传/`BILLGUARD_LLM_BASE_URL`/`BILLGUARD_LLM_MODEL`
- 健康检查:web 打 `/api/health`,MCP 打 `/health`;镜像是 JRE(无 python/curl)→ 用 `wget -q -O-`(alpine 有 busybox wget;temurin 无 → 装curl 或用 java 单行?**方案:temurin 基于 ubuntu,apt 装 curl**)
- MCP server 启动:`--spring.profiles.active=bill|work-item`;web 启动:`java -jar billguard-web-exec.jar`
- CI:GitHub Actions ubuntu-latest,JDK 21 + Docker(Testcontainers 内建),`mvn verify`;主分支推送 + PR 触发
- 每任务 commit;最后 `docker compose up` 本地冒烟(nginx /api/health 200)

---

### Task 1: Dockerfile(多阶段构建)

**Files:** `docker/Dockerfile`(三目标共用:构建层 maven:3.9-temurin-21 → 运行层 eclipse-temurin:21-jre + curl)
- [ ] 构建层:`mvn -pl agent-runtime,billguard-domain,billguard-mcp,billguard-web -am package -DskipTests`(eval 不进镜像)
- [ ] 运行层:拷 agent-runtime/domain/mcp/web 四 jar + 各自 exec;`ENTRYPOINT ["java","-jar"]`,command 由 compose 指定
- [ ] 本地 `docker build` 成功 → Commit `build(docker): 多阶段镜像(temurin-21 JRE)`

### Task 2: docker-compose.yml + nginx.conf

**Files:** `docker-compose.yml`、`docker/nginx.conf`(从 Python 逐项移植)
- [ ] 7 服务 + 2 卷;web_env 锚点;`depends_on` service_healthy;restart: unless-stopped
- [ ] `docker compose config` 校验通过 → Commit `feat(deploy): compose 集群(拓扑逐项对齐)`

### Task 3: GitHub Actions CI

**Files:** `.github/workflows/ci.yml`
- [ ] ubuntu-latest + temurin 21 + docker(Testcontainers 自带);缓存 m2;`mvn verify`;README 徽章
- [ ] Commit `ci: GitHub Actions(mvn verify + Testcontainers)`

### Task 4: 本地集群冒烟 + 文档收尾

- [ ] `docker compose up --build -d` → `curl http://localhost:8080/api/health` = `{"ok":true}`;播种 admin(容器内 CLI);`docker compose kill web-1` 后 nginx 故障转移仍 200;`down` 清理
- [ ] README(求职叙事:双语等价/防御率 1.0/技术栈表)+ development.md(M5 档案 + 运维速查:启停/备份/扩容)
- [ ] Commit `docs+chore: M5 完成 —— 集群冒烟与求职叙事`

## Self-Review

- Python compose 语义逐项核对(ip_hash/fail_timeout/proxy_next_upstream/Host 透传/X-Real-IP/pgdata 卷/健康检查间隔);差异(JDBC DSN 形态、Flyway 免 AUTO_MIGRATE、wget/curl)记录于 compose 注释
- CI 与本地 verify 同一命令,无分叉
