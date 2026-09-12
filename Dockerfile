# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
# 健康检查需要 curl（仅在镜像内安装，不影响宿主）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/target/schoolbus-service.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || curl -fsS http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
