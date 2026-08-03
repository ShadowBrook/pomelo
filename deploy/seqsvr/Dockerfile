# ================================================================
# seqsvr 服务镜像 — StoreSvr / AllocSvr / MediateSvr
#
# 约定：与根 Dockerfile 一致 —— 先在宿主机产出可执行 fat jar，再 COPY 进镜像。
# 原因：本环境 Maven Central 返回 403（宿主机 Maven 依赖 ~/.m2 缓存才能构建），
#       容器内重新下载依赖不可行；宿主机预构建是唯一可靠路径。
#
# 通过 --build-arg MODULE_PATH 指定要打的模块：
#   pomelo-seqsvr/pomelo-seqsvr-store    （StoreSvr）
#   pomelo-seqsvr/pomelo-seqsvr-server   （AllocSvr）
#   pomelo-seqsvr/pomelo-seqsvr-mediate  （MediateSvr）
#
# 宿主机先构建 fat jar（三个模块都要，compose 里已配好）：
#   ./mvnw -DskipTests package -pl pomelo-seqsvr/pomelo-seqsvr-store \
#                              -pl pomelo-seqsvr/pomelo-seqsvr-server \
#                              -pl pomelo-seqsvr/pomelo-seqsvr-mediate -am
# 然后：
#   docker compose -f deploy/seqsvr/docker-compose.yml up -d --build
# ================================================================

FROM eclipse-temurin:21-jdk-ubi10-minimal
RUN useradd --system --create-home pomelo

WORKDIR /app
ARG MODULE_PATH
# 从构建上下文复制对应模块的 fat jar（.dockerignore 已放行 *-fat.jar）
COPY ${MODULE_PATH}/target/*-fat.jar /app/app.jar

# 数据目录（StoreSvr mmap / 路由表持久化；Mediate/Alloc 不写盘，仅为统一挂载）
RUN mkdir -p /data && chown -R pomelo:pomelo /app /data

USER pomelo

ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
