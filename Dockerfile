FROM eclipse-temurin:21-jdk-ubi10-minimal

# 创建非 root 用户
RUN useradd pomelo

WORKDIR /app

# 复制 fat jar（需先在宿主机执行 ./mvnw clean package -DskipTests）
COPY target/pomelo-1.0.0-SNAPSHOT-fat.jar /app/pomelo.jar

# 默认配置文件（运行时可通过 -v 挂载或 POMELO_* 环境变量覆盖）
COPY src/main/resources/conf/config.yaml /app/conf/config.yaml

RUN chown -R pomelo:pomelo /app

USER pomelo

# 9000: TCP Gateway / 9001: WebSocket Gateway / 8080: HTTP API
EXPOSE 9000 9001 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/pomelo.jar"]
