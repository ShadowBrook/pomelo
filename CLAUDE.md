# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Pomelo is an IM (Instant Messaging) server built on **Vert.x 5.0.8** (reactive, non-blocking event-driven framework) with **Java 17** and **Maven**. It exposes a custom binary wire protocol over **TCP (port 9000)** and **WebSocket (port 9001)** dual gateways, with Protobuf and JSON dual codec support. The project is in early development — the protocol layer is complete but most business handlers are stubs.

## Build & Run Commands

```bash
# Run all tests
./mvnw clean test

# Run a single test class
./mvnw clean test -Dtest=TcpGatewayVerticleTest

# Run a single test method
./mvnw clean test -Dtest=TcpGatewayVerticleTest#testHeartbeatMessage

# Build fat jar (output: target/pomelo-1.0.0-SNAPSHOT-fat.jar)
./mvnw clean package

# Run the application (deploys WsGatewayVerticle on port 9001)
./mvnw clean compile exec:java

# Generate Protobuf Java sources from .proto files (also runs on compile)
./mvnw protobuf:compile
```

Tests use JUnit 5 with the Vert.x extension (`@ExtendWith(VertxExtension.class)`). Integration tests use `CountDownLatch` for async coordination. Testcontainers is available for Redis-dependent tests.

## Architecture

### Wire Protocol (`ImMessage`)

The core data structure is `ImMessage` (`common/ImMessage.java`) — a custom binary protocol with:
- **Fixed header**: magic number (`0x504D454C` = "PMEL"), version, codec ID, command (cmd)
- **Variable headers**: key-value string pairs (e.g., targetUserId, status)
- **Body**: raw bytes, interpreted via the codec identified by `codecId`
- Serialization uses a 4-byte length prefix + `encodeToWire()`/`readFromWire()`

TCP uses `RecordParser` with fixed-size mode switching (4-byte length → message body) for sticky packet handling.

### Message Flow

```
Client → Gateway Verticle (TCP/Ws) → ImMessage.readFromWire()
       → MessageDispatcher.dispatch(connection, imMessage)
       → handlerRegistry.get(cmd) → Handler.handle(connection, message)
       → connection.write(response.encodeToWire())
```

### Cmd Routing

`MessageDispatcher` maintains a `Map<Integer, Supplier<MessageHandler>>` registry. Cmd values are defined in `proto/common/common.proto` (the `Cmd` enum). New handlers must be registered in `MessageDispatcher.registerDefaultHandlers()`.

### Connection Abstraction

`Connection` interface (`gateway/handler/Connection.java`) abstracts `NetSocket` and `ServerWebSocket` behind a common API: `write(Buffer)`, `remoteAddress()`, `close()`. The TCP gateway uses `Connection.from(netSocket)`, WebSocket uses `Connection.from(webSocket)`.

### Codec System

Two codec types identified by `codecId`:
- **0 (Protobuf)**: `ProtobufCodec` uses a static `Parser[256]` array indexed by cmd — zero reflection. New proto message types must be registered in the static initializer. Cmd values are capped at 255.
- **1 (JSON)**: `JsonCodec` uses Jackson `ObjectMapper` for arbitrary POJOs.

`CodecRegistry` provides a `cmd × codecId` two-dimensional lookup.

### Handler Hierarchy

- `MessageHandler` — interface: `handle(Connection, ImMessage)`
- `AbstractMessageHandler` — base class providing `sendResponse()`, `sendErrorResponse()`, `decodeProtobuf()`, `encodeProtobuf()`
- Concrete handlers: `HeartbeatHandler`, `LoginHandler`, `LogoutHandler`, `C2CMessageHandler`, `C2GMessageHandler`, `CtrlReqHandler`, `AckReqHandler`, `PullMessageHandler`, `FriendHandler`

### PullMessageHandler — Dual Mode

`PullMessageHandler` serves two purposes via the same `PULL_REQ`/`PULL_RESP` command pair:

1. **Offline message pull** (no `peerId` in body): calls `MessageService.pullOfflineMessages()` → `MessageRepository.pullPending()` — fetches messages with `status < 2` for the requesting user, ordered by `seq`. The message's `seq` is the **recipient's** seqsvr sync version (write-diffusion inbox sequence, assigned at `C2CService.doSend` via `fetchNextSequence(recipientId)`), so `seq > sinceSeq` is the recipient's incremental sync watermark. Used after reconnection.

2. **Conversation history pull** (`peerId` present in body): calls `MessageService.pullConversationHistory(userId, peerId, beforeTime, limit)` which computes `conversationId = buildConversationId(userId, peerId)` (sorted `userId:peerId`) and delegates to `MessageRepository.pullConversation()`. Queries by `conversation_id` with `created_at < beforeTime ORDER BY created_at DESC LIMIT $3`. When `beforeTime` is 0, treated as `Long.MAX_VALUE` (fetch latest). Conversation order is by `created_at` — **not** the per-user `seq`, which mixes two users' inbox spaces and is incomparable.

Both modes now include the message records in the response body (`{code, message, hasMore, messages: [...]}`) — messages are JSON-serialized with fields: `id`, `senderId`, `recipientId`, `msgType`, `content`, `seq`, `createdAt`.

### Proto Definitions

Proto source files live in `src/main/proto/` across 9 domain packages:
- `common.proto` — `Cmd` enum (command codes), `MsgType`, `AckType`, `MessageContent`
- `auth.proto` — AuthReq/Resp, LogoutReq/Resp
- `chat.proto` — C2CReq/Resp/Notify (single chat)
- `group.proto` — C2GReq/Resp/Notify (group chat)
- `ctrl.proto` — CtrlReq/Resp/Notify, CtrlType enum
- `heartbeat.proto` — Ping/Pong
- `ack.proto` — AckReq/Resp/Notify
- `pull.proto` — PullReq/Resp (message pulling)
- `message.proto` — Unified MsgBody (oneof wrapping all message types)

Generated Java classes are output to `src/main/java/com/github/moxib/pomelo/proto/` by the protobuf-maven-plugin.

### Distributed ID Generation

`RedisIdGenerator` implements `IdGenerator` using a Redisson-inspired approach: pre-allocates ID batches locally via Lua scripts on Redis, serves from `AtomicLong` until exhausted, then fetches the next batch. Default allocation size is 5000.

## Key Constraints

- The `ProtobufCodec` static registry is indexed by cmd value (0–255). New proto message cmd values must fit in this range and be registered both in the `ProtobufCodec` static block and in `MessageDispatcher`.
- Currently `MainVerticle` only deploys `WsGatewayVerticle`. To use TCP, deploy `TcpGatewayVerticle` instead or alongside it.
- Ports are configurable via system properties: `gateway.tcp.port` (default 9000), `gateway.websocket.port` (default 9001).
- Editing `.proto` files requires running `./mvnw protobuf:compile` (or `./mvnw clean compile`) to regenerate Java sources **and** `cd src/test/resources && npm run proto` to regenerate proto.js for the JS SDK.

## Coding Style

- **No fully-qualified class names in code body** — always use imports. For example, write `Map<String, String> headers = ...` not `java.util.Map<String, String> headers = ...`. The only exceptions are generated protobuf code (under `proto/` package) which is auto-generated and should not be manually edited.
- **No end-of-line comments** — comments must be placed on their own line above the code they describe, never trailing after code on the same line. For example:
  ```java
  // GOOD
  // 下一个可用 ID
  currentId.set(newStart + 1);

  // BAD
  currentId.set(newStart + 1); // 下一个可用 ID
  ```
