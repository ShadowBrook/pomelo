# Gateway ↔ Logic-Server 模块拆分实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前单体 gateway 模块拆分为 pomelo-common、pomelo-gateway、pomelo-logic-server 三个 Maven 模块，Gateway 与 Logic-Server 通过 Vert.x EventBus 通讯，支持单 JVM 共存（本地 EventBus）和分布式部署（Clustered EventBus）。

**Architecture:** Gateway 退化为薄协议适配层（连接管理 + 协议编解码 + 推送投递），所有业务逻辑（C2C、ACK、Pull、Auth、Heartbeat、Friend、Ctrl）迁移到 logic-server 模块。Gateway 通过 `eventBus.request()` 将客户端请求转发到 logic-server，logic-server 通过 `eventBus.publish()` 将推送广播给所有 gateway 节点，各 gateway 本地查 SessionRegistry 决定是否投递。

**Tech Stack:** Java 17, Maven multi-module, Vert.x 5.0.8, Protobuf 4.31.1, Jackson 2.21.1, PostgreSQL (vertx-pg-client), Redis (vertx-redis-client)

**Design Doc:** `docs/gateway-logic-architecture.md`

## Global Constraints

- 不改变任何业务逻辑行为，纯粹的模块拆分和通讯层替换
- 不改变线协议（ImMessage wire format）
- 不改变 Proto 定义
- 所有现有测试保持通过
- 单 JVM 部署时使用本地 EventBus（无需 Hazelcast 集群配置）
- 代码风格：无 fully-qualified class names、无行尾注释
- Java 17 + Maven wrapper

---

## 文件结构总览

### 拆分前 (current)

```
pomelo/
├── pom.xml (single module, packaging: jar)
└── src/main/java/com/github/moxib/pomelo/
    ├── MainVerticle.java
    ├── api/ApiVerticle.java
    ├── codec/{CodecRegistry,JsonCodec,MessageCodec,ProtobufCodec}.java
    ├── common/{ErrorCode,ImMessage}.java
    ├── config/ConfigHolder.java
    ├── gateway/{TcpGatewayVerticle,WsGatewayVerticle}.java
    ├── gateway/handler/{Connection,MessageDispatcher,SessionRegistry,MessageHandler,AbstractMessageHandler,HeartbeatHandler,LoginHandler,LogoutHandler,C2CMessageHandler,C2GMessageHandler,AckReqHandler,CtrlReqHandler,PullMessageHandler,FriendHandler}.java
    ├── proto/{common,ack,auth,chat,ctrl,group,heartbeat,message,pull,relation}/*.java
    ├── service/{MessageRepository,MessageService,MessageServiceImpl,PgMessageRepository,PgPoolFactory,RedisFactory,RedisOnlineStatus,TokenService}.java
    ├── service/model/{AckNotifyContext,C2CReqContext,C2CRespResult,MessageRecord,UserIdInfo}.java
    ├── service/model/requests/{AckRequest,C2CRequest,CtrlRequest,FriendOpRequest,LoginRequest,PullRequest,SearchRequest}.java
    ├── service/model/responses/{AckResponse,C2CResponse,FriendOpResponse,LoginResponse,SearchResponse}.java
    └── utils/{IdGenerator,NanoIdGenerator,RedisIdGenerator,SnowflakeIdGenerator}.java
```

### 拆分后 (target)

```
pomelo/
├── pom.xml (parent POM, packaging: pom, modules: common, gateway, logic-server)
├── pomelo-common/
│   ├── pom.xml
│   └── src/main/java/com/github/moxib/pomelo/
│       ├── codec/{CodecRegistry,JsonCodec,MessageCodec,ProtobufCodec}.java
│       ├── common/{ErrorCode,ImMessage}.java
│       ├── config/ConfigHolder.java
│       ├── proto/{...} (all generated proto)
│       └── model/PushEnvelope.java (NEW)
├── pomelo-gateway/
│   ├── pom.xml
│   └── src/main/java/com/github/moxib/pomelo/gateway/
│       ├── GatewayMain.java (NEW - standalone launcher)
│       ├── TcpGatewayVerticle.java
│       ├── WsGatewayVerticle.java
│       ├── handler/
│       │   ├── Connection.java
│       │   ├── SessionRegistry.java
│       │   └── MessageDispatcher.java (REWRITTEN - EventBus forwarding)
│       ├── api/ApiVerticle.java
│       └── ApiMain.java (NEW - standalone launcher for HTTP API)
└── pomelo-logic-server/
    ├── pom.xml
    └── src/main/java/com/github/moxib/pomelo/logic/
        ├── LogicMain.java (NEW - standalone launcher)
        ├── LogicVerticle.java (NEW - EventBus consumers)
        ├── service/
        │   ├── AuthService.java (NEW - from LoginHandler + LogoutHandler)
        │   ├── C2CService.java (NEW - from C2CMessageHandler + MessageServiceImpl.sendC2CMessage)
        │   ├── C2GService.java (NEW - from C2GMessageHandler)
        │   ├── AckService.java (NEW - from AckReqHandler + MessageServiceImpl.processAck)
        │   ├── CtrlService.java (NEW - from CtrlReqHandler)
        │   ├── PullService.java (NEW - from PullMessageHandler)
        │   ├── HeartbeatService.java (NEW - from HeartbeatHandler)
        │   └── FriendService.java (NEW - from FriendHandler)
        ├── infrastructure/
        │   ├── MessageRepository.java (from service/)
        │   ├── PgMessageRepository.java (from service/)
        │   ├── PgPoolFactory.java (from service/)
        │   ├── RedisFactory.java (from service/)
        │   ├── RedisOnlineStatus.java (from service/)
        │   └── TokenService.java (from service/)
        ├── id/
        │   ├── IdGenerator.java (from utils/)
        │   ├── RedisIdGenerator.java (from utils/)
        │   ├── NanoIdGenerator.java (from utils/)
        │   └── SnowflakeIdGenerator.java (from utils/)
        └── model/ (from service/model/)
```

---

### Task 1: 创建 Maven 多模块父 POM

**Files:**
- Modify: `pom.xml` (convert to parent POM)
- Create: `pomelo-common/pom.xml`
- Create: `pomelo-gateway/pom.xml`
- Create: `pomelo-logic-server/pom.xml`

**Interfaces:**
- Produces: 三模块 Maven 项目结构，`mvn compile` 可正常编译（尽管模块内尚无源码）

- [ ] **Step 1: 改写根 pom.xml 为父 POM**

将 `pom.xml` 改为 `<packaging>pom</packaging>`，添加 `<modules>` 和子模块共用的 `<dependencyManagement>`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.github.moxib</groupId>
  <artifactId>pomelo</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>pomelo-common</module>
    <module>pomelo-gateway</module>
    <module>pomelo-logic-server</module>
  </modules>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <maven-compiler-plugin.version>3.8.1</maven-compiler-plugin.version>
    <maven-shade-plugin.version>3.2.4</maven-shade-plugin.version>
    <maven-surefire-plugin.version>2.22.2</maven-surefire-plugin.version>
    <exec-maven-plugin.version>3.0.0</exec-maven-plugin.version>

    <vertx.version>5.0.8</vertx.version>
    <junit-jupiter.version>5.9.1</junit-jupiter.version>
    <protobuf.version>4.31.1</protobuf.version>
    <jackson.version>2.21.1</jackson.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-stack-depchain</artifactId>
        <version>${vertx.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>

      <!-- 子模块依赖 -->
      <dependency>
        <groupId>com.github.moxib</groupId>
        <artifactId>pomelo-common</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.moxib</groupId>
        <artifactId>pomelo-gateway</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.moxib</groupId>
        <artifactId>pomelo-logic-server</artifactId>
        <version>${project.version}</version>
      </dependency>

      <!-- 第三方依赖版本统一管理 -->
      <dependency>
        <groupId>com.google.protobuf</groupId>
        <artifactId>protobuf-java</artifactId>
        <version>${protobuf.version}</version>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-core</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.20.1</version>
      </dependency>
      <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson</artifactId>
        <version>3.27.2</version>
      </dependency>
      <dependency>
        <groupId>at.favre.lib</groupId>
        <artifactId>bcrypt</artifactId>
        <version>0.10.2</version>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.9</version>
      </dependency>
      <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.13</version>
      </dependency>

      <!-- Test -->
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-api</artifactId>
        <version>${junit-jupiter.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-engine</artifactId>
        <version>${junit-jupiter.version}</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-junit5</artifactId>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>${maven-compiler-plugin.version}</version>
          <configuration>
            <release>17</release>
            <parameters>true</parameters>
          </configuration>
        </plugin>
        <plugin>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>${maven-surefire-plugin.version}</version>
        </plugin>
        <plugin>
          <groupId>org.xolstice.maven.plugins</groupId>
          <artifactId>protobuf-maven-plugin</artifactId>
          <version>0.6.1</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: 创建 pomelo-common/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.github.moxib</groupId>
    <artifactId>pomelo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>pomelo-common</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.google.protobuf</groupId>
      <artifactId>protobuf-java</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-config</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-config-yaml</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-junit5</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <extensions>
      <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>1.7.1</version>
      </extension>
    </extensions>
    <plugins>
      <plugin>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>1.7.1</version>
        <executions>
          <execution>
            <phase>initialize</phase>
            <goals>
              <goal>detect</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.xolstice.maven.plugins</groupId>
        <artifactId>protobuf-maven-plugin</artifactId>
        <configuration>
          <protocArtifact>com.google.protobuf:protoc:3.25.5:exe:${os.detected.classifier}</protocArtifact>
          <protoSourceRoot>${project.basedir}/src/main/proto</protoSourceRoot>
          <outputDirectory>${project.basedir}/src/main/java</outputDirectory>
          <clearOutputDirectory>false</clearOutputDirectory>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>compile</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 创建 pomelo-gateway/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.github.moxib</groupId>
    <artifactId>pomelo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>pomelo-gateway</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>com.github.moxib</groupId>
      <artifactId>pomelo-common</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-junit5</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <artifactId>maven-shade-plugin</artifactId>
        <version>${maven-shade-plugin.version}</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                    <exclude>META-INF/*.EC</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: 创建 pomelo-logic-server/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.github.moxib</groupId>
    <artifactId>pomelo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>pomelo-logic-server</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>com.github.moxib</groupId>
      <artifactId>pomelo-common</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-pg-client</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-redis-client</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-auth-jwt</artifactId>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-web-client</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.redisson</groupId>
      <artifactId>redisson</artifactId>
    </dependency>
    <dependency>
      <groupId>at.favre.lib</groupId>
      <artifactId>bcrypt</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.vertx</groupId>
      <artifactId>vertx-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
      </plugin>
      <plugin>
        <artifactId>maven-shade-plugin</artifactId>
        <version>${maven-shade-plugin.version}</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                    <exclude>META-INF/*.EC</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 5: 移动资源文件**

将 `src/main/resources`、`src/main/proto`、`src/main/java/com/github/moxib/pomelo/proto/` 保留在原位置不动（等待后续任务迁移到 pomelo-common）。但 proto 文件的 protobuf 编译输出目录需要改为 pomelo-common。先确保空模块结构能通过编译。

```bash
# 创建各子模块的基础目录结构
mkdir -p pomelo-common/src/main/java
mkdir -p pomelo-common/src/main/proto
mkdir -p pomelo-common/src/test/java
mkdir -p pomelo-gateway/src/main/java
mkdir -p pomelo-gateway/src/test/java
mkdir -p pomelo-logic-server/src/main/java
mkdir -p pomelo-logic-server/src/test/java
```

- [ ] **Step 6: 验证 Maven 结构**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS（三个模块均通过编译，虽然代码尚未迁移，空模块会编译成功）。

- [ ] **Step 7: Commit**

```bash
git add pom.xml pomelo-common/pom.xml pomelo-gateway/pom.xml pomelo-logic-server/pom.xml
git commit -m "chore: 建立 Maven 多模块结构 (common/gateway/logic-server)"
```

---

### Task 2: 创建 pomelo-common 模块（共享类迁移）

**Files:**
- Move: `src/main/java/com/github/moxib/pomelo/codec/` → `pomelo-common/src/main/java/com/github/moxib/pomelo/codec/`
- Move: `src/main/java/com/github/moxib/pomelo/common/` → `pomelo-common/src/main/java/com/github/moxib/pomelo/common/`
- Move: `src/main/java/com/github/moxib/pomelo/config/` → `pomelo-common/src/main/java/com/github/moxib/pomelo/config/`
- Move: `src/main/proto/` → `pomelo-common/src/main/proto/`
- Move: `src/main/java/com/github/moxib/pomelo/proto/` → `pomelo-common/src/main/java/com/github/moxib/pomelo/proto/`（生成的 Java 类）
- Create: `pomelo-common/src/main/java/com/github/moxib/pomelo/model/PushEnvelope.java`
- Move: `src/main/resources/` → `pomelo-common/src/main/resources/`（conf/config.yaml 等）
- Move: `src/test/java/com/github/moxib/pomelo/codec/` → `pomelo-common/src/test/java/com/github/moxib/pomelo/codec/`
- Move: `src/test/java/com/github/moxib/pomelo/common/` → `pomelo-common/src/test/java/com/github/moxib/pomelo/common/`

**Interfaces:**
- Produces: `ImMessage`, `ErrorCode`, `CodecRegistry`, `ProtobufCodec`, `JsonCodec`, `MessageCodec`, `ConfigHolder`, `PushEnvelope`
- Produces: 所有 proto 生成的 Java 类（`CommonProto`, `AuthProto`, `ChatProto` 等）

- [ ] **Step 1: 移动 codec 包**

```bash
# 移动源码
cp -r src/main/java/com/github/moxib/pomelo/codec pomelo-common/src/main/java/com/github/moxib/pomelo/codec
rm -rf src/main/java/com/github/moxib/pomelo/codec

# 移动测试
cp -r src/test/java/com/github/moxib/pomelo/codec pomelo-common/src/test/java/com/github/moxib/pomelo/codec
rm -rf src/test/java/com/github/moxib/pomelo/codec
```

不需要修改 codec 包内任何 Java 文件 — 它们只依赖 `com.google.protobuf`、`io.vertx.core.buffer.Buffer`、Jackson，这些都在 pomelo-common 的依赖中。

- [ ] **Step 2: 移动 common 包**

```bash
cp -r src/main/java/com/github/moxib/pomelo/common pomelo-common/src/main/java/com/github/moxib/pomelo/common
rm -rf src/main/java/com/github/moxib/pomelo/common

cp -r src/test/java/com/github/moxib/pomelo/common pomelo-common/src/test/java/com/github/moxib/pomelo/common
rm -rf src/test/java/com/github/moxib/pomelo/common
```

- [ ] **Step 3: 移动 config 包**

```bash
cp -r src/main/java/com/github/moxib/pomelo/config pomelo-common/src/main/java/com/github/moxib/pomelo/config
rm -rf src/main/java/com/github/moxib/pomelo/config
```

- [ ] **Step 4: 移动 proto 定义文件和生成的 Java 类**

```bash
# proto 源文件
cp -r src/main/proto pomelo-common/src/main/proto
rm -rf src/main/proto

# 生成的 proto Java 类
cp -r src/main/java/com/github/moxib/pomelo/proto pomelo-common/src/main/java/com/github/moxib/pomelo/proto
rm -rf src/main/java/com/github/moxib/pomelo/proto
```

更新 pomelo-common/pom.xml 中 protobuf-maven-plugin 的配置，将 `protoSourceRoot` 和 `outputDirectory` 改为正确的路径（它们已是相对 `${project.basedir}` 的，无需改动）。

但需要同步更新 JS SDK 的 proto 生成路径（`src/test/resources/proto.js`）。暂不处理，后续 Task 解决。

- [ ] **Step 5: 移动资源文件**

```bash
cp -r src/main/resources pomelo-common/src/main/resources
rm -rf src/main/resources
```

- [ ] **Step 6: 创建 PushEnvelope**

```java
// pomelo-common/src/main/java/com/github/moxib/pomelo/model/PushEnvelope.java
package com.github.moxib.pomelo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Logic-Server → Gateway 的推送信封。
 * 在 EventBus 上以 JSON 编码传输，各 Gateway 节点收到后根据 targetUserId 查本地 SessionRegistry 投递。
 */
public class PushEnvelope {

  @JsonProperty("targetUserId")
  private String targetUserId;

  @JsonProperty("cmd")
  private int cmd;

  @JsonProperty("body")
  private byte[] body;

  @JsonProperty("codecId")
  private byte codecId;

  @JsonProperty("correlationMsgId")
  private String correlationMsgId;

  public PushEnvelope() {}

  public PushEnvelope(String targetUserId, int cmd, byte[] body, byte codecId) {
    this.targetUserId = targetUserId;
    this.cmd = cmd;
    this.body = body;
    this.codecId = codecId;
  }

  public String getTargetUserId() { return targetUserId; }
  public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

  public int getCmd() { return cmd; }
  public void setCmd(int cmd) { this.cmd = cmd; }

  public byte[] getBody() { return body; }
  public void setBody(byte[] body) { this.body = body; }

  public byte getCodecId() { return codecId; }
  public void setCodecId(byte codecId) { this.codecId = codecId; }

  public String getCorrelationMsgId() { return correlationMsgId; }
  public void setCorrelationMsgId(String correlationMsgId) { this.correlationMsgId = correlationMsgId; }
}
```

- [ ] **Step 7: 验证 pomelo-common 编译和测试**

```bash
./mvnw clean compile -pl pomelo-common
./mvnw test -pl pomelo-common
```

Expected: BUILD SUCCESS，所有 codec 和 ImMessage 测试通过。

- [ ] **Step 8: Commit**

```bash
git add pomelo-common/
git add src/  # 记录删除
git commit -m "feat: 创建 pomelo-common 模块，迁移共享类 (codec/common/config/proto/PushEnvelope)"
```

---

### Task 3: 创建 pomelo-logic-server 模块（基础设施迁移 + Service 层）

**Files:**
- Move: `src/main/java/com/github/moxib/pomelo/service/` → `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/`（Repository、Factory 等） + `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/`（DTO model）
- Move: `src/main/java/com/github/moxib/pomelo/utils/` → `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/id/`
- Move: `src/main/java/com/github/moxib/pomelo/api/ApiVerticle.java` → `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/ApiVerticle.java`

**Interfaces:**
- Produces: `MessageRepository`, `PgMessageRepository`, `MessageService`, `PgPoolFactory`, `RedisFactory`, `RedisOnlineStatus`, `TokenService`, `IdGenerator`, `RedisIdGenerator`, `NanoIdGenerator`, `SnowflakeIdGenerator`, all model DTOs, `ApiVerticle`

- [ ] **Step 1: 创建 logic-server 包目录结构**

```bash
mkdir -p pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/{service,infrastructure,id,model,model/requests,model/responses}
mkdir -p pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic
```

- [ ] **Step 2: 迁移 infrastructure 层（Repository + Factory + Token + Redis）**

```bash
# 移动文件并更新包路径
cp src/main/java/com/github/moxib/pomelo/service/MessageRepository.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/MessageRepository.java

cp src/main/java/com/github/moxib/pomelo/service/PgMessageRepository.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/PgMessageRepository.java

cp src/main/java/com/github/moxib/pomelo/service/PgPoolFactory.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/PgPoolFactory.java

cp src/main/java/com/github/moxib/pomelo/service/RedisFactory.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/RedisFactory.java

cp src/main/java/com/github/moxib/pomelo/service/RedisOnlineStatus.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/RedisOnlineStatus.java

cp src/main/java/com/github/moxib/pomelo/service/TokenService.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/infrastructure/TokenService.java
```

修改每个文件的 `package` 声明：
- `com.github.moxib.pomelo.service` → `com.github.moxib.pomelo.logic.infrastructure`

同时更新所有 import 语句：
- `com.github.moxib.pomelo.service.model.*` → `com.github.moxib.pomelo.logic.model.*`
- `com.github.moxib.pomelo.utils.*` → `com.github.moxib.pomelo.logic.id.*`
- `com.github.moxib.pomelo.codec.*` → 不变（来自 pomelo-common）
- `com.github.moxib.pomelo.common.*` → 不变（来自 pomelo-common）
- `com.github.moxib.pomelo.config.*` → 不变（来自 pomelo-common）
- `com.github.moxib.pomelo.gateway.handler.*` → 删除这些 import（不再依赖 SessionRegistry 和 Connection）

关键修改：`PgMessageRepository` 不再持有 `SessionRegistry` 引用（它本来就没有）。`MessageServiceImpl` 需要大幅改动 — 这个在 Task 5 中处理。

- [ ] **Step 3: 迁移 ID 生成器**

```bash
cp src/main/java/com/github/moxib/pomelo/utils/IdGenerator.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/id/IdGenerator.java

cp src/main/java/com/github/moxib/pomelo/utils/RedisIdGenerator.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/id/RedisIdGenerator.java

cp src/main/java/com/github/moxib/pomelo/utils/NanoIdGenerator.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/id/NanoIdGenerator.java

cp src/main/java/com/github/moxib/pomelo/utils/SnowflakeIdGenerator.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/id/SnowflakeIdGenerator.java
```

修改每个文件的 `package` 声明：
- `com.github.moxib.pomelo.utils` → `com.github.moxib.pomelo.logic.id`

更新 import：`com.github.moxib.pomelo.config.*` → 不变（来自 pomelo-common）

- [ ] **Step 4: 迁移 model 类**

```bash
cp src/main/java/com/github/moxib/pomelo/service/model/*.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/

cp -r src/main/java/com/github/moxib/pomelo/service/model/requests \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/requests

cp -r src/main/java/com/github/moxib/pomelo/service/model/responses \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/model/responses
```

修改每个文件的 `package` 声明：
- `com.github.moxib.pomelo.service.model` → `com.github.moxib.pomelo.logic.model`
- `com.github.moxib.pomelo.service.model.requests` → `com.github.moxib.pomelo.logic.model.requests`
- `com.github.moxib.pomelo.service.model.responses` → `com.github.moxib.pomelo.logic.model.responses`

- [ ] **Step 5: 迁移 MessageService（暂时保留旧实现，后续 Task 重构）**

```bash
cp src/main/java/com/github/moxib/pomelo/service/MessageService.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MessageService.java

cp src/main/java/com/github/moxib/pomelo/service/MessageServiceImpl.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/MessageServiceImpl.java
```

修改 `package`：`com.github.moxib.pomelo.logic.service`

修改 `MessageServiceImpl.java` 的 import — 移除对 `com.github.moxib.pomelo.gateway.handler.SessionRegistry` 和 `com.github.moxib.pomelo.gateway.handler.Connection` 的依赖。

当前 `MessageServiceImpl` 构造函数持有 `SessionRegistry`，在 `pushToRecipient()` 中直接查连接。这个逻辑需要改为通过 EventBus publish。

**暂且保留 MessageServiceImpl 编译所需的 SessionRegistry 引用为空实现**（后续 Task 6 会完整的重构为 C2CService）。

为了能让当前 Task 编译通过，临时处理：
- `MessageServiceImpl` 的 `pushToRecipient()` 方法体暂时注释掉，替换为 TODO 日志。

```java
private void pushToRecipient(MessageRecord record, String senderUserId, String recipientUserId,
                               String senderUserName, String senderNickname) {
  // TODO: Phase 2 — 通过 EventBus publish 推送
  LOG.debug("pushToRecipient 暂未通过 EventBus 实现: msgId={}", record.getId());
}
```

`processAck()` 中的 `sessionRegistry.getUserId()` 也暂时移除（这些在 Task 6 重构时由 AckService 处理）。

**但**：为了 Task 3 能编译通过，我们暂时可以保留 `MessageServiceImpl` 对 `SessionRegistry` 的依赖（通过添加 pomelo-gateway 依赖），但这会造成循环依赖。更好的做法是：先让 `MessageServiceImpl` 编译通过但不完整（移除 SessionRegistry 引用），完整的实现在 Task 6 完成。

在 `MessageServiceImpl` 中：
- 移除 `sessionRegistry` 字段和构造函数参数
- `pushToRecipient()` 暂时为空实现
- `processAck()` 中 `sessionRegistry.getUserId(senderId)` 替换为 `null`

- [ ] **Step 6: 迁移 ApiVerticle**

```bash
cp src/main/java/com/github/moxib/pomelo/api/ApiVerticle.java \
   pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/ApiVerticle.java
```

修改 `package`：`com.github.moxib.pomelo.logic`

更新 import：
- `com.github.moxib.pomelo.service.PgPoolFactory` → `com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory`
- `com.github.moxib.pomelo.service.RedisFactory` → `com.github.moxib.pomelo.logic.infrastructure.RedisFactory`
- `com.github.moxib.pomelo.service.RedisOnlineStatus` → `com.github.moxib.pomelo.logic.infrastructure.RedisOnlineStatus`
- `com.github.moxib.pomelo.service.TokenService` → `com.github.moxib.pomelo.logic.infrastructure.TokenService`
- `com.github.moxib.pomelo.utils.NanoIdGenerator` → `com.github.moxib.pomelo.logic.id.NanoIdGenerator`
- `com.github.moxib.pomelo.utils.SnowflakeIdGenerator` → `com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator`
- `com.github.moxib.pomelo.common.ImMessage` → 不变（common）
- `com.github.moxib.pomelo.codec.*` → 不变（common）
- `com.github.moxib.pomelo.config.ConfigHolder` → 不变（common）

- [ ] **Step 7: 迁移测试类**

```bash
cp src/test/java/com/github/moxib/pomelo/utils/RedisIdGeneratorTest.java \
   pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/id/RedisIdGeneratorTest.java

cp src/test/java/com/github/moxib/pomelo/utils/SnowflakeIdGeneratorTest.java \
   pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/id/SnowflakeIdGeneratorTest.java
```

更新 package 声明和 import 路径。

- [ ] **Step 8: 清理原 src 目录中的已迁移文件**

```bash
rm -rf src/main/java/com/github/moxib/pomelo/service
rm -rf src/main/java/com/github/moxib/pomelo/utils
rm -rf src/main/java/com/github/moxib/pomelo/api
rm -rf src/test/java/com/github/moxib/pomelo/utils
```

注意：此时原 `src/main/java` 中只剩下 `gateway/` 包和 `MainVerticle.java`。

- [ ] **Step 9: 验证编译**

```bash
./mvnw clean compile -pl pomelo-logic-server -am
```

`-am` (also-make) 会先编译 pomelo-common，再编译 pomelo-logic-server。

Expected: BUILD SUCCESS。

- [ ] **Step 10: Commit**

```bash
git add pomelo-logic-server/ src/
git commit -m "feat: 创建 pomelo-logic-server 模块，迁移 infrastructure/model/id/ApiVerticle"
```

---

### Task 4: 重构 pomelo-gateway 模块 — 清理残留

**Files:**
- Move from `src/main/java/com/github/moxib/pomelo/gateway/` to `pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/`
- Move from `src/main/java/com/github/moxib/pomelo/MainVerticle.java` to 暂时保留在根目录（后续 Task 重写）
- Delete: 所有 handler 类（已迁移到 logic-server 的 service 层，Task 5 创建）

**Interfaces:**
- Produces: 纯净的 gateway 模块，仅包含 `TcpGatewayVerticle`, `WsGatewayVerticle`, `Connection`, `SessionRegistry`

**当前 gateway 包中待保留的文件：**
- `TcpGatewayVerticle.java` — 连接管理 + 粘包处理
- `WsGatewayVerticle.java` — WebSocket 连接管理
- `handler/Connection.java` — 连接抽象
- `handler/SessionRegistry.java` — 本地会话管理

**当前 gateway 包中需要删除的文件（已迁移到 logic-server）：**
- 所有 Handler 类（`*Handler.java`, `AbstractMessageHandler.java`, `MessageHandler.java`）
- `MessageDispatcher.java` — 这个需要重写（Task 5）

- [ ] **Step 1: 移动 gateway 包到 pomelo-gateway 模块**

```bash
cp -r src/main/java/com/github/moxib/pomelo/gateway pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway
```

- [ ] **Step 2: 删除 gateway 模块中不再需要的 Handler 类**

```bash
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/AbstractMessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/MessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/HeartbeatHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/LoginHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/LogoutHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/C2CMessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/C2GMessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/AckReqHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/CtrlReqHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/PullMessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/FriendHandler.java
```

保留 `MessageDispatcher.java` — 在 Task 5 中重写。

- [ ] **Step 3: 修复 gateway 模块中保留文件的 import**

`TcpGatewayVerticle.java` 和 `WsGatewayVerticle.java` 原本引用：
- `com.github.moxib.pomelo.common.ImMessage` → 不变（现在来自 pomelo-common）
- `com.github.moxib.pomelo.config.ConfigHolder` → 不变（来自 pomelo-common）
- `com.github.moxib.pomelo.gateway.handler.Connection` → 不变（同模块）
- `com.github.moxib.pomelo.gateway.handler.MessageDispatcher` → 不变（同模块，等待 Task 5 重写）
- `com.github.moxib.pomelo.gateway.handler.SessionRegistry` → 不变
- `com.github.moxib.pomelo.service.RedisOnlineStatus` → 需要改成 `com.github.moxib.pomelo.logic.infrastructure.RedisOnlineStatus`

但 gateway 模块不应该依赖 logic-server！所以 `RedisOnlineStatus` 的调用需要处理。

当前 `TcpGatewayVerticle` 和 `WsGatewayVerticle` 在断连时调用 `RedisOnlineStatus.get(vertx).setOffline(userId)`。在拆分架构中，gateway 不应该直接操作 Redis。两种方案：
1. Gateway 通过 EventBus 通知 logic-server 处理断连（publish 一个 `gateway.user.offline` 事件）
2. 暂时保留跨模块依赖（不推荐）

对于 Phase 1，采用方案 1：Gateway 发布 `gateway.user.offline` 事件，logic-server 订阅处理。

但为了 Task 4 能编译通过，暂时注释掉 `RedisOnlineStatus` 调用，留下 TODO。

```java
// TcpGatewayVerticle.java — socket.closeHandler 中
socket.closeHandler(v -> {
    LOG.info("TCP 客户端断开连接：{}", socket.remoteAddress());
    String userId = sessionRegistry.unregisterByConnection(conn);
    // TODO: Phase 2 — publish gateway.user.offline event
    // updateUserOffline(userId);
});
```

移除 `RedisOnlineStatus` import，移除 `updateUserOffline()` 方法。

同样修改 `WsGatewayVerticle.java`。

`SessionRegistry.java` 无外部依赖变更，不需要修改。

- [ ] **Step 4: 删除原 src 中的 gateway 目录**

```bash
rm -rf src/main/java/com/github/moxib/pomelo/gateway
```

- [ ] **Step 5: 验证编译**

```bash
./mvnw clean compile -pl pomelo-gateway -am
```

Expected: BUILD SUCCESS（pomelo-common 编译通过，pomelo-gateway 编译通过）。

- [ ] **Step 6: Commit**

```bash
git add pomelo-gateway/ src/
git commit -m "feat: 重构 pomelo-gateway 模块，删除 Handler 类，移除 RedisOnlineStatus 依赖"
```

---

### Task 5: 重写 Gateway MessageDispatcher（EventBus 转发 + Push 订阅）

**Files:**
- Modify: `pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/MessageDispatcher.java`

**Interfaces:**
- Consumes: `ImMessage`, `Connection`, `SessionRegistry`, `PushEnvelope`（from pomelo-common）
- Produces: EventBus-based dispatch — `eventBus.request("logic.xxx", buffer)` + `eventBus.consumer("gateway.push")`

- [ ] **Step 1: 重写 MessageDispatcher**

```java
package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

/**
 * 消息分发器 — Gateway 侧。
 * 将客户端请求通过 EventBus request() 转发到 logic-server，
 * 并订阅 gateway.push 地址，将 logic-server 的推送投递给本地连接。
 */
public class MessageDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

  private final Vertx vertx;
  private final SessionRegistry sessionRegistry;

  public MessageDispatcher(Vertx vertx, SessionRegistry sessionRegistry) {
    this.vertx = vertx;
    this.sessionRegistry = sessionRegistry;
    // 订阅推送地址
    vertx.eventBus().consumer("gateway.push", this::onPushMessage);
  }

  /**
   * 收到 logic-server 的推送消息，投递给本地连接的用户。
   */
  private void onPushMessage(io.vertx.core.eventbus.Message<JsonObject> msg) {
    PushEnvelope env = msg.body().mapTo(PushEnvelope.class);
    Connection conn = sessionRegistry.getConnectionByUserId(env.getTargetUserId());
    if (conn == null) {
      LOG.debug("push target {} 不在本节点，忽略", env.getTargetUserId());
      return;
    }
    ImMessage imMsg = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(env.getCodecId())
      .cmd(env.getCmd())
      .messageId(env.getCorrelationMsgId() != null ? env.getCorrelationMsgId() : "")
      .body(env.getBody())
      .build();
    conn.write(imMsg.encodeToWire());
  }

  /**
   * 将客户端请求转发到 logic-server。
   * ImMessage 编码为 wire bytes（Buffer）在 EventBus 上传输。
   */
  public void dispatch(Connection connection, ImMessage message) {
    String address = cmdToAddress(message.getCmd());
    if (address == null) {
      handleUnknownCmd(connection, message);
      return;
    }
    Buffer wire = message.encodeToWire();
    vertx.eventBus().request(address, wire, reply -> {
      if (reply.succeeded()) {
        Buffer respBuf = (Buffer) reply.result().body();
        connection.write(respBuf);
      } else {
        LOG.warn("EventBus request failed: address={} cause={}", address, reply.cause().getMessage());
        sendErrorToClient(connection, message, reply.cause().getMessage());
      }
    });
  }

  /**
   * cmd → EventBus 地址映射。
   */
  private static String cmdToAddress(int cmd) {
    if (cmd == CMD_C2C_REQ_VALUE)          return "logic.c2c";
    if (cmd == CMD_C2G_REQ_VALUE)          return "logic.c2g";
    if (cmd == CMD_AUTH_REQ_VALUE)         return "logic.auth";
    if (cmd == CMD_LOGOUT_REQ_VALUE)       return "logic.auth";
    if (cmd == CMD_CTRL_REQ_VALUE)         return "logic.ctrl";
    if (cmd == CMD_ACK_REQ_VALUE)          return "logic.ack";
    if (cmd == CMD_PULL_REQ_VALUE)         return "logic.pull";
    if (cmd == CMD_PING_VALUE)             return "logic.ping";
    if (cmd == CMD_FRIEND_SEARCH_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_ADD_REQ_VALUE)    return "logic.friend";
    if (cmd == CMD_FRIEND_ACCEPT_REQ_VALUE) return "logic.friend";
    if (cmd == CMD_FRIEND_DELETE_REQ_VALUE) return "logic.friend";
    return null;
  }

  private void handleUnknownCmd(Connection connection, ImMessage request) {
    String msg = "Unknown cmd: 0x" + Integer.toHexString(request.getCmd());
    byte codecId = request.getCodecId();
    byte[] body;
    if (codecId == 0) {
      body = CommonProto.ErrorBody.newBuilder()
        .setCode(ErrorCode.UNKNOWN_CMD.getCode()).setMessage(msg).build().toByteArray();
    } else {
      body = new JsonObject().put("code", ErrorCode.UNKNOWN_CMD.getCode()).put("message", msg)
        .toBuffer().getBytes();
    }
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  private void sendErrorToClient(Connection connection, ImMessage request, String detail) {
    byte codecId = request.getCodecId();
    byte[] body;
    if (codecId == 0) {
      body = CommonProto.ErrorBody.newBuilder()
        .setCode(ErrorCode.INTERNAL_ERROR.getCode()).setMessage(detail).build().toByteArray();
    } else {
      body = new JsonObject().put("code", ErrorCode.INTERNAL_ERROR.getCode()).put("message", detail)
        .toBuffer().getBytes();
    }
    ImMessage response = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(CMD_ERROR_VALUE)
      .messageId(request.getMessageId())
      .body(body)
      .build();
    connection.write(response.encodeToWire());
  }

  public SessionRegistry getSessionRegistry() {
    return sessionRegistry;
  }
}
```

注意：`MessageDispatcher` 不再持有 `CodecRegistry`、`MessageRepository`、`IdGenerator` 等共享依赖，也不再创建 Handler。所有转换逻辑移到 logic-server。

**但**：`TcpGatewayVerticle` 和 `WsGatewayVerticle` 创建 `MessageDispatcher` 时传入的参数需要更新。

当前代码：
```java
dispatcher = new MessageDispatcher(vertx);
return dispatcher.start()...
```

改为：
```java
sessionRegistry = new SessionRegistry();
dispatcher = new MessageDispatcher(vertx, sessionRegistry);
```

移除 `dispatcher.start()` 调用（不再需要异步初始化 Redis）。

同时修改 `TcpGatewayVerticle` 和 `WsGatewayVerticle` 中 `dispatcher.getSessionRegistry()` 的调用 — 改为直接使用本地的 `sessionRegistry` 引用。

- [ ] **Step 2: 更新 TcpGatewayVerticle — 使用本地 sessionRegistry + 移除 RedisOnlineStatus**

完整重写 `TcpGatewayVerticle.java`：

```java
package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.handler.Connection;
import com.github.moxib.pomelo.gateway.handler.MessageDispatcher;
import com.github.moxib.pomelo.gateway.handler.SessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP 网关。
 */
public class TcpGatewayVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(TcpGatewayVerticle.class);

  private int tcpPort;
  private NetServer tcpServer;
  private SessionRegistry sessionRegistry;
  private MessageDispatcher dispatcher;

  @Override
  public Future<?> start() {
    this.tcpPort = ConfigHolder.getInt("gateway.tcp.port", 9000);
    LOG.info("启动 TCP Gateway，端口：{}", tcpPort);

    this.sessionRegistry = new SessionRegistry();
    this.dispatcher = new MessageDispatcher(vertx, sessionRegistry);
    tcpServer = vertx.createNetServer();

    return tcpServer
      .connectHandler(getTcpHandler())
      .listen(tcpPort)
      .onSuccess(ar -> LOG.info("TCP Gateway 已启动，监听端口：{}", tcpPort))
      .onFailure(throwable -> LOG.error("TCP Gateway 启动失败", throwable));
  }

  @Override
  public Future<?> stop() {
    LOG.info("停止 TCP Gateway");
    return tcpServer != null ? tcpServer.close() : Future.succeededFuture();
  }

  private Handler<NetSocket> getTcpHandler() {
    return socket -> {
      RecordParser parser = RecordParser.newFixed(4);
      Connection conn = Connection.from(socket);

      Handler<Buffer> handler = new Handler<>() {
        int size = -1;

        @Override
        public void handle(Buffer buff) {
          if (size == -1) {
            size = buff.getInt(0);
            parser.fixedSizeMode(size);
          } else {
            ImMessage imMessage = new ImMessage();
            imMessage.readFromWire(buff);
            parser.fixedSizeMode(4);
            size = -1;
            dispatcher.dispatch(conn, imMessage);
          }
        }
      };

      parser.setOutput(handler);
      socket.handler(parser);

      socket.exceptionHandler(throwable -> {
        if (throwable instanceof SocketException || throwable.getMessage().contains("Connection reset")) {
          LOG.debug("TCP 连接重置：{}", socket.remoteAddress());
        } else {
          LOG.error("TCP 连接异常：{}", socket.remoteAddress(), throwable);
        }
        String userId = sessionRegistry.unregisterByConnection(conn);
        // TODO: Phase 2 — publish gateway.user.offline event to EventBus
        socket.close();
      });

      socket.closeHandler(v -> {
        LOG.info("TCP 客户端断开连接：{}", socket.remoteAddress());
        String userId = sessionRegistry.unregisterByConnection(conn);
        // TODO: Phase 2 — publish gateway.user.offline event to EventBus
      });
    };
  }
}
```

对 `WsGatewayVerticle.java` 做同样修改。

- [ ] **Step 3: 删除 MessageHandler.java 和 AbstractMessageHandler.java**

```bash
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/MessageHandler.java
rm pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/handler/AbstractMessageHandler.java
```

这两个文件不再需要 — gateway 不再有 handler 接口/抽象类。

- [ ] **Step 4: 验证编译**

```bash
./mvnw clean compile -pl pomelo-gateway -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add pomelo-gateway/
git commit -m "feat: 重写 MessageDispatcher 为 EventBus 转发模式，简化 Gateway Verticle"
```

---

### Task 6: 创建 LogicVerticle + Service 层（业务逻辑迁移）

**Files:**
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicVerticle.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2CService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/AckService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/AuthService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/PullService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/HeartbeatService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/CtrlService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/C2GService.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/service/FriendService.java`

**Interfaces:**
- Consumes: `ImMessage`, `CodecRegistry`, `MessageRepository`, `IdGenerator`, `PushEnvelope`, `TokenService`, `RedisOnlineStatus`（from pomelo-common + 本模块 infrastructure）
- Produces: `LogicVerticle` 注册所有 EventBus consumer，各 Service 处理对应业务逻辑

- [ ] **Step 1: 创建 LogicVerticle**

```java
package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.RedisIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.PgMessageRepository;
import com.github.moxib.pomelo.logic.infrastructure.PgPoolFactory;
import com.github.moxib.pomelo.logic.infrastructure.RedisFactory;
import com.github.moxib.pomelo.logic.service.*;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic-Server 主 Verticle。
 * 注册所有 EventBus consumer，负责将请求分发给对应的 Service。
 */
public class LogicVerticle extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogicVerticle.class);

  private C2CService c2cService;
  private AckService ackService;
  private AuthService authService;
  private PullService pullService;
  private HeartbeatService heartbeatService;
  private CtrlService ctrlService;
  private C2GService c2gService;
  private FriendService friendService;

  @Override
  public Future<?> start() {
    return RedisFactory.get(vertx).connect()
      .compose(v -> {
        var messageRepo = new PgMessageRepository(vertx);
        var idGenerator = new RedisIdGenerator(vertx, RedisFactory.get(vertx).getRedis());

        c2cService = new C2CService(vertx, messageRepo, idGenerator);
        ackService = new AckService(vertx, messageRepo);
        authService = new AuthService(vertx);
        pullService = new PullService(vertx, messageRepo);
        heartbeatService = new HeartbeatService();
        ctrlService = new CtrlService();
        c2gService = new C2GService();
        friendService = new FriendService(vertx, messageRepo);

        var bus = vertx.eventBus();
        bus.consumer("logic.c2c",     (Message<Buffer> msg) -> dispatch(msg, c2cService::process));
        bus.consumer("logic.ack",     (Message<Buffer> msg) -> dispatch(msg, ackService::process));
        bus.consumer("logic.auth",    (Message<Buffer> msg) -> dispatch(msg, authService::process));
        bus.consumer("logic.pull",    (Message<Buffer> msg) -> dispatch(msg, pullService::process));
        bus.consumer("logic.ping",    (Message<Buffer> msg) -> dispatch(msg, heartbeatService::process));
        bus.consumer("logic.ctrl",    (Message<Buffer> msg) -> dispatch(msg, ctrlService::process));
        bus.consumer("logic.c2g",     (Message<Buffer> msg) -> dispatch(msg, c2gService::process));
        bus.consumer("logic.friend",  (Message<Buffer> msg) -> dispatch(msg, friendService::process));

        LOG.info("LogicVerticle 已启动，所有 EventBus consumer 注册完成");
        return Future.succeededFuture();
      });
  }

  /**
   * 通用分发：Buffer → ImMessage → Service → Buffer reply。
   */
  private void dispatch(Message<Buffer> msg, java.util.function.Function<ImMessage, Future<ImMessage>> processor) {
    try {
      ImMessage request = new ImMessage();
      request.readFromWire(msg.body());
      processor.apply(request).onComplete(ar -> {
        if (ar.succeeded()) {
          msg.reply(ar.result().encodeToWire());
        } else {
          LOG.error("Service 处理失败: {}", ar.cause().getMessage());
          msg.fail(500, ar.cause().getMessage());
        }
      });
    } catch (Exception e) {
      LOG.error("消息解码失败", e);
      msg.fail(400, "Bad request: " + e.getMessage());
    }
  }

  @Override
  public Future<?> stop() {
    PgPoolFactory.close();
    RedisFactory.get(vertx).close();
    return Future.succeededFuture();
  }
}
```

每个 Service 实现 `Function<ImMessage, Future<ImMessage>>` 接口 — 接收 ImMessage 请求，返回 ImMessage 响应。

- [ ] **Step 2: 创建 HeartbeatService（最简单，先验证模式正确）**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import io.vertx.core.Future;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PONG_VALUE;

public class HeartbeatService {

  public Future<ImMessage> process(ImMessage message) {
    ImMessage pong = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(message.getCodecId())
      .cmd(CMD_PONG_VALUE)
      .messageId(message.getMessageId())
      .build();
    return Future.succeededFuture(pong);
  }
}
```

- [ ] **Step 3: 创建 C2CService**

从 `C2CMessageHandler` + `MessageServiceImpl.sendC2CMessage()` 合并而来，并将 `pushToRecipient()` 改为 `eventBus.publish()`。

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.IdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.C2CReqContext;
import com.github.moxib.pomelo.logic.model.C2CRespResult;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.C2CRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.google.protobuf.ByteString;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2C_NOTIFY_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_C2C_RESP_VALUE;

public class C2CService {

  private static final Logger LOG = LoggerFactory.getLogger(C2CService.class);

  private final Vertx vertx;
  private final MessageRepository messageRepo;
  private final IdGenerator idGenerator;

  public C2CService(Vertx vertx, MessageRepository messageRepo, IdGenerator idGenerator) {
    this.vertx = vertx;
    this.messageRepo = messageRepo;
    this.idGenerator = idGenerator;
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      C2CRequest req = decodeC2CRequest(message);
      C2CRequest.MessageBody msg = req.message();
      String senderUserId = extractSenderUserId(message, req.senderId());
      String recipientUserId = req.recipientId();
      String content = msg.content();
      int msgType = msg.msgType();
      long clientMsgId = req.messageId() != 0 ? req.messageId() : parseWireMessageId(message);
      long timestamp = req.timestamp() != 0 ? req.timestamp() : System.currentTimeMillis();

      if (senderUserId == null || senderUserId.isEmpty() || recipientUserId == null || recipientUserId.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "senderId 和 recipientId 不能为空"));
      }

      long senderId;
      try { senderId = Long.parseLong(senderUserId); }
      catch (NumberFormatException e) { senderId = 0L; }

      return resolveId(recipientUserId).compose(recipientId -> {
        if (recipientId == 0) {
          return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.NOT_FOUND, "接收者不存在"));
        }

        C2CReqContext ctx = C2CReqContext.builder()
          .messageId(clientMsgId)
          .senderId(senderId)
          .recipientId(recipientId)
          .senderUserId(senderUserId)
          .msgType(msgType)
          .content(content)
          .timestamp(timestamp)
          .build();

        return doSend(ctx)
          .map(result -> buildC2CResponse(message, codecId, result));
      });
    } catch (Exception e) {
      LOG.error("C2C 消息处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_C2C_RESP_VALUE, ErrorCode.BAD_REQUEST, "消息格式错误：" + e.getMessage()));
    }
  }

  private Future<C2CRespResult> doSend(C2CReqContext ctx) {
    return idGenerator.nextId()
      .compose(seq -> {
        long now = System.currentTimeMillis();
        String convId = buildConversationId(ctx.getSenderId(), ctx.getRecipientId());
        MessageRecord record = MessageRecord.builder()
          .id(ctx.getMessageId())
          .senderId(ctx.getSenderId())
          .recipientId(ctx.getRecipientId())
          .conversationId(convId)
          .msgType(ctx.getMsgType())
          .content(ctx.getContent())
          .seq(seq)
          .status(0)
          .createdAt(now)
          .build();

        return messageRepo.save(record)
          .map(inserted -> {
            if (inserted) {
              // 通过 EventBus publish 推送 C2CNotify 给所有 gateway 节点
              publishC2CNotify(record, ctx.getSenderUserId());
            }
            return C2CRespResult.builder()
              .code(0).message("success")
              .messageId(ctx.getMessageId())
              .seq(seq).serverTime(now)
              .build();
          });
      });
  }

  /** 构造 C2CNotify 并通过 EventBus 广播给所有 Gateway */
  private void publishC2CNotify(MessageRecord record, String senderUserId) {
    // 构建 PB 格式的 C2CNotify body（Gateway 侧根据 recipient codec 决定最终编码）
    CommonProto.MessageContent msgContent = CommonProto.MessageContent.newBuilder()
      .setMsgTypeValue(record.getMsgType())
      .setContent(ByteString.copyFromUtf8(record.getContent() != null ? record.getContent() : ""))
      .build();
    ChatProto.C2CNotify notify = ChatProto.C2CNotify.newBuilder()
      .setSenderId(senderUserId)
      .setRecipientId(String.valueOf(record.getRecipientId()))
      .setMessage(msgContent)
      .setSeq(record.getSeq())
      .build();

    // 推送给 recipient
    PushEnvelope env = new PushEnvelope(
      String.valueOf(record.getRecipientId()),
      CMD_C2C_NOTIFY_VALUE,
      notify.toByteArray(),
      (byte) 0
    );
    vertx.eventBus().publish("gateway.push", JsonObject.mapFrom(env));
    LOG.debug("C2CNotify 已广播: recipientId={} msgId={} seq={}", record.getRecipientId(), record.getId(), record.getSeq());
  }

  private C2CRequest decodeC2CRequest(ImMessage message) {
    var codec = new CodecRegistry();
    // 使用 codec cmd 注册表（复用原 MessageDispatcher 中的注册逻辑）
    codec.registerProtobuf(CMD_C2C_REQ_VALUE, ChatProto.C2CReq.parser(), C2CRequest::fromProto, C2CRequest.class);
    codec.registerJson(CMD_C2C_REQ_VALUE, C2CRequest.class);
    return codec.getCodec(message.getCmd(), message.getCodecId()).decode(message.getBody());
  }

  // ... 省略重复的辅助方法（与 C2CMessageHandler 中相同）：
  // resolveId(), extractSenderUserId(), parseWireMessageId(), buildConversationId()
  // buildErrorResp(), buildC2CResponse()
}
```

**注意**：完整的 C2CService 需要复用原 `C2CMessageHandler` 中的 `resolveId()`, `extractSenderUserId()`, `parseWireMessageId()` 辅助方法，以及 `buildErrorResp()`, `buildC2CResponse()` 等响应构建方法。为了避免重复，可以提取一个 `ServiceBase` 基类放在 logic-server 模块中。

- [ ] **Step 4: 创建 ServiceBase 基类（提取公共编解码 + 响应构建逻辑）**

从原 `AbstractMessageHandler` 提取核心方法：

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public abstract class ServiceBase {

  protected ImMessage buildResponse(ImMessage request, int cmd, Object body) {
    byte codecId = request.getCodecId();
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId(codecId)
      .cmd(cmd)
      .messageId(request.getMessageId())
      .body(encodeBody(codecId, cmd, body))
      .build();
  }

  protected ImMessage buildErrorResp(ImMessage request, int responseCmd, ErrorCode error, String detail) {
    String msg = detail != null ? detail : error.getDefaultMessage();
    byte codecId = request.getCodecId();
    Object body = codecId == ProtobufCodec.CODEC_ID
      ? CommonProto.ErrorBody.newBuilder().setCode(error.getCode()).setMessage(msg).build()
      : new JsonObject().put("code", error.getCode()).put("message", msg);
    return buildResponse(request, responseCmd, body);
  }

  protected byte[] encodeBody(byte codecId, int cmd, Object body) {
    var codec = new CodecRegistry().getCodec(cmd, codecId);
    if (codec != null) {
      try { return codec.encode(body); }
      catch (UnsupportedOperationException ignored) {}
    }
    if (codecId == ProtobufCodec.CODEC_ID && body instanceof com.google.protobuf.Message msg) {
      return msg.toByteArray();
    }
    if (body instanceof JsonObject jo) {
      return jo.toBuffer().getBytes();
    }
    if (body != null) {
      return JsonObject.mapFrom(body).toBuffer().getBytes();
    }
    throw new RuntimeException("Cannot encode null body");
  }

  protected String getUserIdFromHeaders(ImMessage message) {
    Map<String, String> headers = message.getVarHeaders();
    return headers != null ? headers.get("userId") : null;
  }

  protected String getBodyAsString(ImMessage message) {
    if (message.getBody() == null || message.getBody().length == 0) return null;
    return new String(message.getBody(), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 5: 创建 AuthService（Login + Logout）**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.RedisOnlineStatus;
import com.github.moxib.pomelo.logic.infrastructure.TokenService;
import com.github.moxib.pomelo.logic.model.requests.LoginRequest;
import com.github.moxib.pomelo.proto.auth.AuthProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AuthService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

  private final Vertx vertx;
  private final CodecRegistry codecRegistry;

  public AuthService(Vertx vertx) {
    this.vertx = vertx;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_AUTH_REQ_VALUE, AuthProto.AuthReq.parser(), LoginRequest::fromProto, LoginRequest.class);
    codecRegistry.registerJson(CMD_AUTH_REQ_VALUE, LoginRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      int cmd = message.getCmd();
      if (cmd == CMD_AUTH_REQ_VALUE) return handleLogin(message);
      if (cmd == CMD_LOGOUT_REQ_VALUE) return handleLogout(message);
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.BAD_REQUEST, "Unknown auth cmd"));
    } catch (Exception e) {
      LOG.error("Auth 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.INTERNAL_ERROR, e.getMessage()));
    }
  }

  private Future<ImMessage> handleLogin(ImMessage message) {
    try {
      LoginRequest req = codecRegistry.getCodec(message.getCmd(), message.getCodecId()).decode(message.getBody());
      String token = req.token();

      if (token == null || token.isEmpty()) {
        return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.BAD_REQUEST, "token 不能为空"));
      }

      TokenService ts = TokenService.get(vertx);
      return ts.isBlacklisted(token).compose(blacklisted -> {
        if (blacklisted) {
          LOG.warn("Token 已被撤销");
          return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.UNAUTHORIZED, "Token 已失效"));
        }
        return ts.validate(token).compose(claims -> {
          if (claims == null) {
            return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.UNAUTHORIZED, "Token 无效"));
          }
          String userId = claims.getString("sub");
          long id = claims.getLong("id", 0L);
          String userName = claims.getString("userName");
          String nickname = claims.getString("nickname");
          byte codecId = message.getCodecId();

          LOG.info("登录成功: userId={} userName={} codec={}", userId, userName, codecId == 0 ? "PB" : "JSON");
          RedisOnlineStatus.get(vertx).setOnline(userId);

          // Login 成功后，Gateway 需要将 session 注册到 SessionRegistry。
          // 通过 varHeaders 把用户信息带给 gateway（Gateway 在收到响应后提取并注册）。
          // 策略：响应 varHeaders 中携带 userId/id/userName/nickname，Gateway dispatch 回调中注册。

          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = AuthProto.AuthResp.newBuilder()
              .setCode(0).setMessage("success").setUserId(userId).build();
          } else {
            respBody = new JsonObject()
              .put("code", 0).put("message", "success")
              .put("userId", userId).put("userName", userName)
              .put("nickname", nickname != null ? nickname : "");
          }

          ImMessage response = buildResponse(message, CMD_AUTH_RESP_VALUE, respBody);
          // 将用户身份信息放入 varHeaders，Gateway 在 dispatch 回调中提取并注册到 SessionRegistry
          response.getVarHeaders().put("loginUserId", userId);
          response.getVarHeaders().put("loginId", String.valueOf(id));
          response.getVarHeaders().put("loginUserName", userName != null ? userName : "");
          response.getVarHeaders().put("loginNickname", nickname != null ? nickname : "");
          response.getVarHeaders().put("loginCodecId", String.valueOf(codecId));
          response.getVarHeaders().put("loginToken", token);
          return Future.succeededFuture(response);
        });
      });
    } catch (Exception e) {
      LOG.error("登录处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_AUTH_RESP_VALUE, ErrorCode.INTERNAL_ERROR, "登录失败：" + e.getMessage()));
    }
  }

  private Future<ImMessage> handleLogout(ImMessage message) {
    String userId = getUserIdFromHeaders(message);
    if (userId == null) userId = getBodyAsString(message);
    LOG.info("登出请求: userId={}", userId);

    byte codecId = message.getCodecId();
    if (userId != null && !userId.isEmpty()) {
      RedisOnlineStatus.get(vertx).setOffline(userId);
    }

    // Token 黑名单处理：token 从 varHeaders 传入
    String token = null;
    Map<String, String> headers = message.getVarHeaders();
    if (headers != null) token = headers.get("token");
    if (token != null && !token.isEmpty() && !"test-token".equals(token)) {
      TokenService.get(vertx).blacklist(token);
    }

    Object respBody = codecId == ProtobufCodec.CODEC_ID
      ? AuthProto.LogoutResp.newBuilder().setCode(0).setMessage("登出成功").build()
      : new JsonObject().put("code", 0).put("message", "登出成功");

    ImMessage response = buildResponse(message, CMD_LOGOUT_RESP_VALUE, respBody);
    response.getVarHeaders().put("status", "success");
    // 告知 Gateway 注销 session
    response.getVarHeaders().put("logoutUserId", userId != null ? userId : "");
    return Future.succeededFuture(response);
  }
}
```

- [ ] **Step 6: 创建 AckService**

```java
package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.codec.CodecRegistry;
import com.github.moxib.pomelo.codec.ProtobufCodec;
import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.AckNotifyContext;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.requests.AckRequest;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.ack.AckProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;

public class AckService extends ServiceBase {

  private static final Logger LOG = LoggerFactory.getLogger(AckService.class);

  private final Vertx vertx;
  private final MessageRepository messageRepo;
  private final CodecRegistry codecRegistry;

  public AckService(Vertx vertx, MessageRepository messageRepo) {
    this.vertx = vertx;
    this.messageRepo = messageRepo;
    this.codecRegistry = new CodecRegistry();
    codecRegistry.registerProtobuf(CMD_ACK_REQ_VALUE, AckProto.AckReq.parser(), AckRequest::fromProto, AckRequest.class);
    codecRegistry.registerJson(CMD_ACK_REQ_VALUE, AckRequest.class);
  }

  public Future<ImMessage> process(ImMessage message) {
    try {
      byte codecId = message.getCodecId();
      AckRequest req = codecRegistry.getCodec(message.getCmd(), message.getCodecId()).decode(message.getBody());
      List<Long> messageIds = req.messageIds();
      int ackType = req.ackType();

      if (ackType != CommonProto.AckType.RECEIVED_VALUE && ackType != CommonProto.AckType.SEEN_VALUE) {
        return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "无效的 ackType: " + ackType));
      }

      boolean isSeen = ackType == CommonProto.AckType.SEEN_VALUE;
      String ackFromUserId = getUserIdFromHeaders(message);

      LOG.info("ACK: {} msgs {} codec={}", messageIds.size(), isSeen ? "SEEN" : "RECEIVED", codecId == 0 ? "PB" : "JSON");

      return processAckInternal(messageIds, ackType)
        .map(notifyContexts -> {
          // 广播 AckNotify
          for (AckNotifyContext ctx : notifyContexts) {
            publishAckNotify(ctx);
          }

          Object respBody;
          if (codecId == ProtobufCodec.CODEC_ID) {
            respBody = AckProto.AckResp.newBuilder().setAckTypeValue(ackType).build();
          } else {
            respBody = new JsonObject().put("ackType", ackType);
          }
          return buildResponse(message, CMD_ACK_RESP_VALUE, respBody);
        });
    } catch (Exception e) {
      LOG.error("ACK 处理失败", e);
      return Future.succeededFuture(buildErrorResp(message, CMD_ACK_RESP_VALUE, ErrorCode.BAD_REQUEST, "ACK 格式错误: " + e.getMessage()));
    }
  }

  private Future<List<AckNotifyContext>> processAckInternal(List<Long> messageIds, int ackType) {
    if (messageIds == null || messageIds.isEmpty()) {
      return Future.succeededFuture(Collections.emptyList());
    }
    int newStatus = (ackType == CommonProto.AckType.RECEIVED_VALUE) ? 1 : 2;

    return messageRepo.findByIds(messageIds)
      .compose(records -> {
        if (records.isEmpty()) {
          LOG.warn("ACK: 未找到匹配消息 count={}", messageIds.size());
          return Future.succeededFuture(Collections.<AckNotifyContext>emptyList());
        }
        return messageRepo.batchUpdateStatus(messageIds, newStatus)
          .map(v -> {
            Map<Long, List<Long>> senderMessages = new LinkedHashMap<>();
            for (MessageRecord r : records) {
              senderMessages.computeIfAbsent(r.getSenderId(), k -> new ArrayList<>()).add(r.getId());
            }
            List<AckNotifyContext> results = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : senderMessages.entrySet()) {
              results.add(new AckNotifyContext(entry.getKey(), null, entry.getValue(), ackType));
            }
            LOG.info("ACK: updated {} msgs to status={}, notify {} senders", messageIds.size(), newStatus, results.size());
            return results;
          });
      });
  }

  /** 通过 EventBus 广播 AckNotify 推送 */
  private void publishAckNotify(AckNotifyContext ctx) {
    try {
      AckProto.AckNotify.Builder builder = AckProto.AckNotify.newBuilder()
        .setAckTypeValue(ctx.getAckType());
      for (Long id : ctx.getMessageIds()) {
        builder.addMessageIds(id);
      }
      AckProto.AckNotify notify = builder.build();

      PushEnvelope env = new PushEnvelope(
        String.valueOf(ctx.getSenderId()),
        CMD_ACK_NOTIFY_VALUE,
        notify.toByteArray(),
        (byte) 0
      );
      vertx.eventBus().publish("gateway.push", JsonObject.mapFrom(env));
      LOG.debug("AckNotify 已广播: sender={} type={} count={}", ctx.getSenderId(), ctx.getAckType(), ctx.getMessageIds().size());
    } catch (Exception e) {
      LOG.error("推送 AckNotify 失败: {}", e.getMessage());
    }
  }
}
```

- [ ] **Step 7: 创建 PullService、CtrlService、C2GService、FriendService**

PullService — 从 `PullMessageHandler` 迁移，保持相同的 decode → resolve → query → response 流程。

CtrlService — 从 `CtrlReqHandler` 迁移（当前为 stub）。

C2GService — 从 `C2GMessageHandler` 迁移（当前为 stub）。

FriendService — 从 `FriendHandler` 迁移，将 `pushNotify()` 中的直接 Connection 查找改为 EventBus publish。

（这些 Service 的实现模式与 C2CService/AckService 相同，为节省篇幅在此省略完整代码，实现时参考原 Handler 逻辑 + EventBus publish 模式。）

- [ ] **Step 8: 更新 MessageDispatcher — 支持 Login/Logout 响应中的 Session 注册**

Gateway 的 `MessageDispatcher.dispatch()` 在收到 EventBus 响应后，需要检查响应中的特殊 varHeaders 并执行 Session 操作。

```java
public void dispatch(Connection connection, ImMessage message) {
    String address = cmdToAddress(message.getCmd());
    if (address == null) {
      handleUnknownCmd(connection, message);
      return;
    }
    Buffer wire = message.encodeToWire();
    vertx.eventBus().request(address, wire, reply -> {
      if (reply.succeeded()) {
        Buffer respBuf = (Buffer) reply.result().body();
        // 解析响应，检查是否需要注册/注销 session
        ImMessage response = new ImMessage();
        response.readFromWire(respBuf);
        handleSessionUpdates(connection, response);
        connection.write(respBuf);
      } else {
        sendErrorToClient(connection, message, reply.cause().getMessage());
      }
    });
  }

  private void handleSessionUpdates(Connection connection, ImMessage response) {
    Map<String, String> headers = response.getVarHeaders();
    if (headers == null) return;

    // Login 成功 → 注册 session
    String loginUserId = headers.get("loginUserId");
    if (loginUserId != null && !loginUserId.isEmpty()) {
      long id = Long.parseLong(headers.getOrDefault("loginId", "0"));
      String userName = headers.getOrDefault("loginUserName", "");
      String nickname = headers.getOrDefault("loginNickname", "");
      byte codecId = Byte.parseByte(headers.getOrDefault("loginCodecId", "0"));
      String token = headers.getOrDefault("loginToken", "");
      sessionRegistry.register(loginUserId, id, connection, codecId, userName, nickname, token);
      LOG.info("Session 已注册: userId={} id={}", loginUserId, id);
    }

    // Logout 成功 → 注销 session
    String logoutUserId = headers.get("logoutUserId");
    if (logoutUserId != null && !logoutUserId.isEmpty()) {
      sessionRegistry.unregisterByUserId(logoutUserId);
      LOG.info("Session 已注销: userId={}", logoutUserId);
    }
  }
```

- [ ] **Step 9: 编译验证**

```bash
./mvnw clean compile -pl pomelo-logic-server -am
```

Expected: BUILD SUCCESS。

- [ ] **Step 10: Commit**

```bash
git add pomelo-logic-server/
git commit -m "feat: 创建 LogicVerticle + 所有 Service（C2C/Ack/Auth/Pull/Heartbeat/Ctrl/C2G/Friend），EventBus 通讯"
```

---

### Task 7: 创建启动入口 + 端到端验证

**Files:**
- Create: `pomelo-gateway/src/main/java/com/github/moxib/pomelo/gateway/GatewayMain.java`
- Create: `pomelo-logic-server/src/main/java/com/github/moxib/pomelo/logic/LogicMain.java`
- Delete: `src/main/java/com/github/moxib/pomelo/MainVerticle.java`（不再需要）
- Delete: `src/main/java/com/github/moxib/pomelo/`（清空剩余 src 目录）

**Interfaces:**
- Produces: 可独立启动的 Gateway 进程和 Logic-Server 进程，或同 JVM 共存的测试启动器

- [ ] **Step 1: 创建 GatewayMain.java**

```java
package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway 独立启动入口。
 * 部署 TcpGatewayVerticle 和 WsGatewayVerticle。
 */
public class GatewayMain extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new TcpGatewayVerticle()),
        vertx.deployVerticle(new WsGatewayVerticle())
      ))
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new GatewayMain())
      .onSuccess(id -> LOG.info("Gateway 已启动: deploymentId={}", id))
      .onFailure(e -> LOG.error("Gateway 启动失败", e));
  }
}
```

- [ ] **Step 2: 创建 LogicMain.java**

```java
package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic-Server 独立启动入口。
 * 部署 LogicVerticle（EventBus consumer）+ ApiVerticle（HTTP API）。
 */
public class LogicMain extends VerticleBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogicMain.class);

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new LogicVerticle()),
        vertx.deployVerticle(new ApiVerticle())
      ))
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new LogicMain())
      .onSuccess(id -> LOG.info("Logic-Server 已启动: deploymentId={}", id))
      .onFailure(e -> LOG.error("Logic-Server 启动失败", e));
  }
}
```

- [ ] **Step 3: 创建单 JVM 共存测试入口（TestMainVerticle）**

```java
// pomelo-logic-server/src/test/java/com/github/moxib/pomelo/logic/TestMainVerticle.java
package com.github.moxib.pomelo.logic;

import com.github.moxib.pomelo.config.ConfigHolder;
import com.github.moxib.pomelo.gateway.TcpGatewayVerticle;
import com.github.moxib.pomelo.gateway.WsGatewayVerticle;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

/**
 * 单 JVM 共存测试入口：同时部署 Gateway + Logic-Server + API。
 * 用于本地开发和集成测试。
 */
public class TestMainVerticle extends VerticleBase {

  @Override
  public Future<?> start() {
    return ConfigHolder.load(vertx)
      .compose(v -> Future.all(
        vertx.deployVerticle(new TcpGatewayVerticle()),
        vertx.deployVerticle(new WsGatewayVerticle()),
        vertx.deployVerticle(new LogicVerticle()),
        vertx.deployVerticle(new ApiVerticle())
      ))
      .mapEmpty();
  }

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new TestMainVerticle());
  }
}
```

- [ ] **Step 4: 清理原 src 目录**

```bash
rm -rf src/
```

- [ ] **Step 5: 更新测试目录结构**

移动 gateway 测试：
```bash
mkdir -p pomelo-gateway/src/test/java/com/github/moxib/pomelo/gateway
# 如果原 src/test 中有 gateway 测试，移过去
```

更新 `pomelo-gateway/pom.xml` 添加对 `pomelo-logic-server` 的 test 依赖（test scope，仅用于 TestMainVerticle 共存测试）：

```xml
<dependency>
  <groupId>com.github.moxib</groupId>
  <artifactId>pomelo-logic-server</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 6: 全量编译 + 运行现有测试**

```bash
./mvnw clean compile
./mvnw test
```

Expected: BUILD SUCCESS，所有模块编译通过，现有测试全部通过。

- [ ] **Step 7: 端到端集成测试**

创建一个简单的集成测试验证 EventBus 通讯链路：

```java
// pomelo-gateway/src/test/java/com/github/moxib/pomelo/gateway/EventBusIntegrationTest.java
package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.LogicVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PING_VALUE;
import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PONG_VALUE;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class EventBusIntegrationTest {

  @Test
  void testPingPongViaEventBus(Vertx vertx, VertxTestContext ctx) {
    vertx.deployVerticle(new LogicVerticle())
      .onSuccess(id -> {
        // 构造 PING 消息
        ImMessage ping = ImMessage.builder()
          .magic(ImMessage.MAGIC_NUMBER)
          .version(ImMessage.WIRE_PROTOCOL_VERSION)
          .codecId((byte) 0)
          .cmd(CMD_PING_VALUE)
          .messageId("test-1")
          .build();

        Buffer wire = ping.encodeToWire();
        vertx.eventBus().request("logic.ping", wire, reply -> {
          ctx.verify(() -> {
            assertTrue(reply.succeeded());
            Buffer respBuf = (Buffer) reply.result().body();
            ImMessage pong = new ImMessage();
            pong.readFromWire(respBuf);
            assertEquals(CMD_PONG_VALUE, pong.getCmd());
            assertEquals("test-1", pong.getMessageId());
            ctx.completeNow();
          });
        });
      })
      .onFailure(ctx::failNow);
  }
}
```

- [ ] **Step 8: 运行端到端集成测试**

```bash
./mvnw test -pl pomelo-gateway
```

Expected: EventBusIntegrationTest PASS。

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: 创建启动入口 (GatewayMain/LogicMain/TestMainVerticle)，端到端集成测试通过"
```

---

## 验证清单

- [ ] `./mvnw clean compile` 全量编译通过
- [ ] `./mvnw test` 全部测试通过
- [ ] `ImMessageTest` 通过（pomelo-common 模块）
- [ ] `ProtobufCodecTest` 通过（pomelo-common 模块）
- [ ] `JsonCodecTest` 通过（pomelo-common 模块）
- [ ] `TcpGatewayVerticleTest` 通过（pomelo-gateway 模块）
- [ ] `RedisIdGeneratorTest` 通过（pomelo-logic-server 模块）
- [ ] `SnowflakeIdGeneratorTest` 通过（pomelo-logic-server 模块）
- [ ] `EventBusIntegrationTest` 通过（端到端 Ping/Pong）
- [ ] Gateway 启动不报错（无 RedisOnlineStatus 依赖）
- [ ] LogicVerticle 所有 consumer 注册成功
- [ ] 单 JVM 内 Gateway → EventBus → Logic-Server → Gateway push 链路通畅

---

## 后续 Phase 规划

### Phase 2 — 业务补全 + 推送机制完善

1. **推送 codec 自适应**：Gateway `onPushMessage()` 根据目标用户注册的 codecId 动态编码推送消息（PB/JSON）
2. **离线事件**：Gateway 发布 `gateway.user.offline` 事件，Logic-Server 订阅并更新 Redis 在线状态
3. **Session 同步**：Login/Logout 响应通过 varHeaders 通知 Gateway 管理本地 SessionRegistry（已在本次实现部分支持）
4. **C2CNotify codec 切换**：根据接收方 codecId 选择 PB 或 JSON 编码推送内容

### Phase 3 — 分布式部署

1. 配置 Hazelcast 集群发现（`hazelcast.xml` 或 `cluster.xml`）
2. 部署多 Gateway 节点 + 多 Logic-Server 节点
3. 验证 EventBus 集群模式下的消息路由和推送广播
4. 压力测试验证广播推送性能
5. 如需优化，引入中心化路由表（Redis 存储 `userId → gatewayNodeId`）
