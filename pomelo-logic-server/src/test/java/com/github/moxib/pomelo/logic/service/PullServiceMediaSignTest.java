package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.proto.common.CommonProto;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PullServiceMediaSignTest {

  private static final MessageRecord IMAGE = MessageRecord.builder()
    .id(1L).senderId(10L).recipientId(20L).conversationId("10:20")
    .msgType(2).content("{\"key\":\"image/10/x.jpg\"}")
    .seq(1L).status(0).createdAt(1L).build();

  private final MessageRepository stubRepo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) { return Future.succeededFuture(true); }
    @Override public Future<Void> updateStatus(long messageId, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<Void> batchUpdateStatus(List<Long> ids, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
      return Future.succeededFuture(List.of(IMAGE));
    }
    @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<List<MessageRecord>> pullConversation(String cid, long before, int limit) {
      return Future.succeededFuture(List.of());
    }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
      return Future.succeededFuture(Map.of());
    }
  };

  @Test
  void mediaContentGetsSignedUrlOnPull() throws Exception {
    MediaUrlSigner signer = (msgType, content) -> content + "?signed";
    PullService service = new PullService(stubRepo, signer);

    byte[] body = new JsonObject().put("userId", "20").put("seq", 0L).put("limit", 10)
      .toBuffer().getBytes();
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 1).cmd(CommonProto.Cmd.CMD_PULL_REQ_VALUE)
      .messageId("p-1").body(body).varHeaders(new HashMap<>()).build();

    ImMessage resp = service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    JsonObject json = new JsonObject(new String(resp.getBody(), StandardCharsets.UTF_8));
    String content = json.getJsonArray("messages").getJsonObject(0).getString("content");
    assertTrue(content.endsWith("?signed"), "拉取消息 content 应被签名: " + content);
  }
}