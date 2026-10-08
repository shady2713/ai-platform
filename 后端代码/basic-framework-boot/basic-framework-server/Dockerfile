# 阶段一：仅解包 layered jar，供运行阶段按层拷贝以最大化构建缓存复用
FROM eclipse-temurin:24.0.2_12-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2 AS layers
WORKDIR /build
COPY ./target/basic-framework-server.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:24.0.2_12-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2

# JRE 基座不含 curl：仅安装 curl 供 HEALTHCHECK 探测，--no-install-recommends
# 并清理 apt 索引以控制体积；基座附带但运行期不用的 pebble 工具显式移除。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -f /usr/bin/pebble \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行。prod 日志写入 ${user.home}/logs（application-prod.yaml），
# --create-home 保证 app 用户 home 存在且可写。
RUN useradd --create-home --shell /usr/sbin/nologin app

WORKDIR /basic-framework-server
# Spring Boot layertools 标准四段式；文件保持 root 所有，app 用户只读。
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandom"
ENV ARGS=""

EXPOSE 48080

USER app

# 生产仅暴露 /actuator/health（application-prod.yaml）；首次启动含 Flyway 迁移，start-period 放宽。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:48080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar application.jar ${ARGS}"]
