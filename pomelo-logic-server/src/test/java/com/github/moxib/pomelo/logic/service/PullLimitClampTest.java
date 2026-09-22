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
import com.google.protobuf.Message;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 拉取条数上限：请求里的 limit 是客户端可控值，服务端必须在触库前截断，
 * 否则一条指令即可让服务端为单次响应分配巨型缓冲（群拉取还会逐条 presign）。
 */
class PullLimitClampTest {

  private static final int MAX_PULL_LIMIT = 200;
  private static final int DEFAULT_PULL_LIMIT = 50;

  private final AtomicInteger c2cLimit = new AtomicInteger(-1);
  private final AtomicInteger groupLimit = new AtomicInteger(-1);

  private final MessageRepository msgRepo = new MessageRepository() {
    @Override public Future<Boolean> save(MessageRecord record) { return Future.succeededFuture(true); }
    @Override public Future<Void> batchUpdateStatus(long recipientId, List<Long> ids, int newStatus) { return Future.succeededFuture(); }
    @Override public Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit) {
      c2cLimit.set(limit);
      return Future.succeededFuture(List.of());
    }
    @Override public Future<MessageRecord> findById(long messageId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> findByIds(long recipientId, List<Long> ids) { return Future.succeededFuture(List.of()); }
    @Override public Future<MessageRecord> findBySenderAndClientMsgId(long senderId, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<MessageRecord>> pullConversation(String cid, long before, int limit) {
      c2cLimit.set(limit);
      return Future.succeededFuture(List.of());
    }
    @Override public Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids) {
      return Future.succeededFuture(Map.of());
    }
  };

  private final GroupRepository groupRepo = new GroupRepository() {
    @Override public Future<GroupInfo> findById(long id) { return Future.succeededFuture(GroupInfo.builder().id(id).build()); }
    @Override public Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward) {
      groupLimit.set(limit);
      return Future.succeededFuture(List.of());
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

  private static ImMessage req(int cmd, Message body) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", "20");
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(cmd)
      .messageId("limit-1").body(body.toByteArray()).varHeaders(headers).build();
  }

  private static void await(Future<ImMessage> f) throws Exception {
    f.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("离线拉取：超大 limit 被截到 maxPullLimit")
  void offlinePullClampsHugeLimit() throws Exception {
    PullService service = new PullService(msgRepo, (msgType, content) -> content);
    await(service.process(req(CommonProto.Cmd.CMD_PULL_REQ_VALUE,
      PullProto.PullReq.newBuilder().setLimit(1_000_000).setSeq(0).build())));
    assertEquals(MAX_PULL_LIMIT, c2cLimit.get());
  }

  @Test
  @DisplayName("离线拉取：limit 未设置时用默认值")
  void offlinePullUsesDefaultWhenUnset() throws Exception {
    PullService service = new PullService(msgRepo, (msgType, content) -> content);
    await(service.process(req(CommonProto.Cmd.CMD_PULL_REQ_VALUE,
      PullProto.PullReq.newBuilder().setLimit(0).setSeq(0).build())));
    assertEquals(DEFAULT_PULL_LIMIT, c2cLimit.get());
  }

  @Test
  @DisplayName("离线拉取：合法 limit 原样透传")
  void offlinePullKeepsReasonableLimit() throws Exception {
    PullService service = new PullService(msgRepo, (msgType, content) -> content);
    await(service.process(req(CommonProto.Cmd.CMD_PULL_REQ_VALUE,
      PullProto.PullReq.newBuilder().setLimit(20).setSeq(0).build())));
    assertEquals(20, c2cLimit.get());
  }

  @Test
  @DisplayName("群消息拉取：超大 limit 被截到 maxPullLimit")
  void groupPullClampsHugeLimit() throws Exception {
    GroupPullService service = new GroupPullService(groupRepo, msgRepo, (msgType, content) -> content);
    await(service.process(req(CommonProto.Cmd.CMD_GROUP_PULL_MSG_REQ_VALUE,
      PullProto.PullGroupMsgReq.newBuilder().setGroupId(100L).setCursor(0L).setLimit(1_000_000).build())));
    assertEquals(MAX_PULL_LIMIT, groupLimit.get());
  }
}
