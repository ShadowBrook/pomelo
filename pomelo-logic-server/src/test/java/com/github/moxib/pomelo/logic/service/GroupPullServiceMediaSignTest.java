package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContext;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.infrastructure.MessageRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.pull.PullProto;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GroupPullServiceMediaSignTest {

  private static final GroupMsgWithSender IMAGE = new GroupMsgWithSender(
    1L, 10L, 100L, 2, "{\"key\":\"image/10/x.jpg\"}", null, 1L, 1L);

  private final GroupRepository stubGroupRepo = new GroupRepository() {
    @Override public Future<GroupInfo> findById(long id) {
      return Future.succeededFuture(GroupInfo.builder().id(id).build());
    }
    @Override public Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward) {
      return Future.succeededFuture(List.of(IMAGE));
    }
    @Override public Future<GroupMemberContext> getGroupMemberContext(long g, long u) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> createGroup(GroupInfo group) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<List<GroupInfo>> findGroupsByUserId(long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<List<GroupMemberRecord>> findMembers(long groupId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> addMember(long id, long groupId, long userId, int role, long now) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> removeMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override
  public Future<Integer> transferOwnership(long groupId, long fromOwnerId, long toUserId) {
    return Future.succeededFuture(0);
  }

  @Override
  public Future<Integer> dissolveGroup(long groupId, long ownerId) {
    return Future.succeededFuture(0);
  }

  @Override
  public Future<Integer> updateGroupName(long groupId, String name) {
    return Future.succeededFuture(0);
  }

public Future<Boolean> isMember(long groupId, long userId) { return Future.succeededFuture(true); }
    @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> isFriend(long a, long b) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, String ext, long seq, long createdAt, long clientMsgId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long sender, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
  };

  private final MessageRepository stubMsgRepo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) { return Future.succeededFuture(true); }
    @Override public Future<Void> batchUpdateStatus(long recipientId, List<Long> ids, int status) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findById(long id) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(long recipientId, List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findBySenderAndClientMsgId(long senderId, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> pullConversation(String cid, long before, int limit) { return Future.succeededFuture(List.of()); }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) { return Future.succeededFuture(Map.of()); }
  };

  @Test
  void mediaContentGetsSignedUrlOnGroupPull() throws Exception {
    MediaUrlSigner signer = (msgType, content) -> content + "?signed";
    GroupPullService service = new GroupPullService(stubGroupRepo, stubMsgRepo, signer);

    PullProto.PullGroupMsgReq body = PullProto.PullGroupMsgReq.newBuilder()
      .setGroupId(100L).setCursor(0L).setLimit(10).setIsBackward(false).build();
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "10");
    ImMessage req = ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CommonProto.Cmd.CMD_GROUP_PULL_MSG_REQ_VALUE)
      .messageId("g-1").body(body.toByteArray()).varHeaders(headers).build();

    ImMessage resp = service.process(req).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    PullProto.PullResp pull = PullProto.PullResp.parseFrom(resp.getBody());
    assertEquals(1, pull.getMessagesCount());
    String content = pull.getMessages(0).getContent().toStringUtf8();
    assertTrue(content.endsWith("?signed"), "群拉取消息 content 应被签名: " + content);
  }
}