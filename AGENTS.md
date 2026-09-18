# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Pomelo is an IM (Instant Messaging) server built on **Vert.x 5** (reactive, non-blocking) with **Java 21** and **Maven multi-module** layout. It exposes a custom binary wire protocol over **TCP (default 9000)** and **WebSocket (default 9001)** gateways. The wire codec is **Protobuf-only** (codecId frozen to 0; the legacy JSON codec has been removed).

### Modules

| Module | Responsibility |
|--------|----------------|
| `pomelo-common` | Wire protocol (`ImMessage`), Protobuf codec registry, config (`ConfigHolder`), session route table, JWT parser, push envelope codec, generated proto classes |
| `pomelo-gateway` | TCP/WS gateways, connection/session management, EventBus forwarding to logic, push delivery |
| `pomelo-logic-server` | Business services (C2C/C2G/groups/friends/ack/pull/auth/upload), PostgreSQL repositories, Redis, HTTP API (default 8888), snowflake ID generation |
| `pomelo-seqsvr` | Sequence server (alloc / store / mediate / client SDK) — per-recipient monotonic seq allocation with lease-based failover |
| `pomelo-benchmark` | Load-test client and metrics |

## Build & Run Commands

```bash
# Run all tests
./mvnw clean test

# Run a single test class / method
./mvnw test -pl pomelo-gateway -am -Dtest=TcpGatewayVerticleTest
./mvnw test -pl pomelo-gateway -am -Dtest=TcpGatewayVerticleTest#testHeartbeatMessage

# Build fat jars
./mvnw clean package

# Generate Protobuf Java sources (also runs during compile)
./mvnw protobuf:compile
```

Notes:
- Redis-dependent tests use Testcontainers (requires Docker). `RedisIdGeneratorTest` is legacy and may be skipped: `-Dtest='!RedisIdGeneratorTest'`.
- Java 21 is required; set `JAVA_HOME` accordingly.
- Editing `.proto` files requires `./mvnw protobuf:compile` (runs automatically on compile) to regenerate Java sources under `pomelo-common/src/main/java/com/github/moxib/pomelo/proto/`.

## Architecture

### Request Flow

```
Client → Gateway Verticle (TCP/Ws, RecordParser framing)
       → MessageDispatcher.dispatch(connection, imMessage)
           - identity: taken from SessionRegistry (token verified at AUTH), never from client headers
           - unauthenticated connections: only CMD_PING / CMD_AUTH_REQ are forwarded
       → EventBus (logic.c2c / logic.c2g / logic.group / logic.pull / ...)
       → Logic service → PgMessageRepository / SeqClientService → reply buffer → client
```

### Identity Trust Chain (important)

`userId`/`userName`/`nickname` variable headers are **server-controlled**: the gateway overwrites them from the authenticated session (`MessageDispatcher.normalizeSenderHeaders`) before forwarding. Logic services must treat these headers as the only identity source; body-embedded identity fields (`senderId`, `userId`) are informational only. Ownership checks still happen in SQL (`recipient_id` predicates) as defense in depth.

### Message Flow (C2C)

1. `C2CService` validates identity, allocates a snowflake message id and a recipient-scoped seq from seqsvr, persists to `im_message_c2c` (idempotent by `(sender_id, client_msg_id)` retry lookup), then pushes `C2CNotify` via `PushRouter`.
2. Recipients sync via `PULL_REQ` (`seq > sinceSeq` on their inbox) or receive pushes; conversation history pull is ordered by `created_at DESC, id DESC`.

### seqsvr

- **AllocSvr** (`AllocManager`): in-memory per-id cur_seq, section max persisted to StoreSvr in SEQ_STEP-aligned batches. Lease: route-table sync every 4s; stops serving after 15s without a successful store read, or when `saveMaxSeq` stays unsaved past the same threshold (prevents seq reissue). Newly assigned sections activate after `2 × syncLease` pending delay (guarantees the previous owner stopped).
- **MediateSvr**: assigns sections to alloc nodes, regenerates the router table. Router updates are **persist-first**: the in-memory/returned version only advances after the Store write quorum succeeds (prevents version rollback on restart — see `docs/2026-09-07-seqsvr-subscription-loss-incident.md`).
- **StoreSvr**: mmap-backed per-set files, optional NRW multi-replica client (`ReplicatedStoreClient`).
- **Client** (`SeqClientService`): caches router by version, retries on `ROUTE_OUTDATED` with embedded-router adoption, force-adopt and Mediate pull escape hatches (incident P8).

### Session Routing

Gateways register per-user routes in a clustered `SessionRouteTable` (Vert.x cluster-wide map, no TTL; node liveness is a separate TTL'd heartbeat). Pushes are precise-route only — there is **no broadcast fallback**: pushes to offline users (no route) or dead nodes are dropped and recovered by the client's seq + ACK + PULL offline sync (see `docs/2026-09-18-remove-push-broadcast-design.md`); route deletion is conditional (`removeIfPresent`) so stale disconnects never clobber a newer node's route. Non-clustered single-process mode delivers pushes via a local point-to-point EventBus `send` (`gateway.push`); clustered gateways only subscribe to `gateway.push.<nodeId>` — both sides branch on `SessionRouteTable.isRoutingAvailable()`.

## Design Docs

- `docs/2026-09-08-reply-and-forward-message-design.md` — 引用（快照式 ReplySnippet + reply_json 列 + 服务端反查覆盖）与转发（单条零协议改动 / 合并 FORWARD=8 + 签名器嵌套注入）设计（未实施）
- `docs/2026-09-18-remove-push-broadcast-design.md` — 取消推送广播兜底：离线/死节点丢弃由 seq + PULL 补偿，单进程改本地点对点投递
- `docs/2026-09-08-message-model-and-conversation-key-design.md` — 消息扩散模型（单聊写扩散信箱 / 群聊读融合时间线）与会话键（c2c conversation_id / group group_id）设计决策
- `docs/2026-09-07-seqsvr-subscription-loss-incident.md` — 2026-09-07 seqsvr 订阅丢失事故分析与加固（P1–P9）

## Key Constraints

- `ProtobufCodec` static registry is indexed by cmd (0–255 array + overflow map for `CMD_ERROR = 0xFFFF`). New cmds must register a parser there and a route in `MessageDispatcher.cmdToAddress`.
- TCP/WS frames share the same wire format: 4-byte length prefix + `ImMessage`. Frame limits live in `ImMessage` (`MAX_FRAME_SIZE` etc.) and are enforced in both gateways.
- `GatewayMain` deploys TCP and WS verticles **sharing one `SessionRegistry`/`MessageDispatcher`** — two dispatchers on one node would split the `gateway.push.<nodeId>` consumer and drop pushes.
- Ports/config: `gateway.tcp.port`, `gateway.websocket.port`, `api.http.port`, `seqsvr.*` (see `conf/config.yaml`); env overrides with `POMELO_` prefix.
- PostgreSQL schema: `db/schema.sql` — c2c messages are HASH-partitioned by `sender_id`, group messages by `group_id` (the partition key participates in the retry-idempotency unique key, as required by PG for partitioned-table constraints). Message-send idempotency is atomic: `INSERT ... ON CONFLICT (sender_id, client_msg_id) DO NOTHING` / `(group_id, sender_id, client_msg_id)`, with a follow-up lookup returning the original message on conflict.

## Coding Style

- **No fully-qualified class names in code body** — always use imports. Generated protobuf code under `proto/` is the only exception.
- **No end-of-line comments** — comments go on their own line above the code:
  ```java
  // GOOD
  // 下一个可用 ID
  currentId.set(newStart + 1);

  // BAD
  currentId.set(newStart + 1); // 下一个可用 ID
  ```
