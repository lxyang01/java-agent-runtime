# 多阶段构建:web 与两个 MCP server 共用同一镜像,靠 command 换入口。
# 构建层用完整 JDK + Maven;运行层只要 JRE + curl(健康检查)。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY agent-runtime/pom.xml agent-runtime/pom.xml
COPY billguard-domain/pom.xml billguard-domain/pom.xml
COPY billguard-mcp/pom.xml billguard-mcp/pom.xml
COPY billguard-web/pom.xml billguard-web/pom.xml
COPY billguard-eval/pom.xml billguard-eval/pom.xml
# 依赖层缓存:先解析再拷源(go-offline 失败不阻断 —— 下一层会补)
RUN mvn -q -pl agent-runtime,billguard-domain,billguard-mcp,billguard-web -am dependency:go-offline -DskipTests || true
COPY agent-runtime agent-runtime
COPY billguard-domain billguard-domain
COPY billguard-mcp billguard-mcp
COPY billguard-web billguard-web
COPY billguard-eval/src billguard-eval/src
RUN mvn -q -pl agent-runtime,billguard-domain,billguard-mcp,billguard-web -am package -DskipTests

FROM eclipse-temurin:21-jre
# 健康检查需要 HTTP 客户端(JRE 无 python/wget)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/billguard-web/target/billguard-web-*-exec.jar /app/web.jar
COPY --from=build /src/billguard-mcp/target/billguard-mcp-*-exec.jar /app/mcp.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar"]
# compose 的 command 只给 jar 与 JVM 参数,例如:
#   web:   ["/app/web.jar"]
#   bill:  ["-Dspring.profiles.active=bill", "/app/mcp.jar"]
