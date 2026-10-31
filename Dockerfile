# syntax=docker/dockerfile:1
# =====================================================================
# 后端镜像（Spring Boot + OR-Tools CP-SAT）
#
# 必须用 glibc 基础镜像：OR-Tools 原生库是 gnu 工具链编的，alpine/musl
# 会直接 UnsatisfiedLinkError。JDK 与 JRE 都用 Debian/Ubuntu 系。
# =====================================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom 拉依赖：改业务代码不会重新下载整个 Maven 仓库
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests package \
 && cp target/campus-ai-platform-*.jar /build/app.jar

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre-jammy

# curl 用于容器健康检查；tzdata 保证 Asia/Shanghai 可用
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl tzdata fontconfig \
 && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 非 root 运行；固定 uid/gid 便于宿主机挂载目录时对齐权限
RUN groupadd -g 10001 campus \
 && useradd -u 10001 -g campus -m -s /bin/bash campus

WORKDIR /app
COPY --from=build --chown=campus:campus /build/app.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R campus:campus /app

USER campus
EXPOSE 8080

# start-period 给足：首次启动要跑 Flyway 迁移
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
