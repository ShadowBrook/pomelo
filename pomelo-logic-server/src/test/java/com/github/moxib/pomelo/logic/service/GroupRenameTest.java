package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.logic.id.SnowflakeIdGenerator;
import com.github.moxib.pomelo.logic.infrastructure.GroupMemberContext;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgReader;
import com.github.moxib.pomelo.logic.infrastructure.GroupMsgWithSender;
import com.github.moxib.pomelo.logic.infrastructure.GroupRepository;
import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.group.GroupMgmtProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群名修改：权限（群主/管理员）、落库、INFO_UPDATED 通知携带新群名并扇出到全体成员
 * （推送经 PushRouter 按端型扇出，操作者自己的其他端同样收到，实现多端回显）。
 */
@DisplayName("群名修改")
class GroupRenameTest {

  private static final long GROUP_ID = 500L;
  private static final long OWNER_ID = 100L;
  private static final long ADMIN_ID = 101L;
  private static final long MEMBER_ID = 102L;

  private Vertx vertx;
  private FakeGroupRepo repo;
  private final List<PushEnvelope> pushes = new ArrayList<>();
  private GroupManagementService service;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    repo = new FakeGroupRepo();
    service = new GroupManagementService(new PushRouter(vertx) {
      @Override
      public void push(PushEnvelope env) {
        pushes.add(env);
      }
    }, repo, new SnowflakeIdGenerator(1), null, null);
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) {
      vertx.close();
    }
  }

  private static GroupMemberRecord member(long userId, int role) {
    return GroupMemberRecord.builder().groupId(GROUP_ID).userId(userId)
      .userName("u" + userId).nickname("n" + userId).role(role).joinedAt(1L).build();
  }

  private static ImMessage updateReq(long operatorId, String groupId, String name) {
    GroupMgmtProto.UpdateGroupReq.Builder b = GroupMgmtProto.UpdateGroupReq.newBuilder()
      .setGroupId(groupId == null ? 0 : Long.parseLong(groupId));
    if (name != null) {
      b.setName(name);
    }
    return reqOf(operatorId, b);
  }

  private static ImMessage updateDescReq(long operatorId, String groupId, String description) {
    GroupMgmtProto.UpdateGroupReq.Builder b = GroupMgmtProto.UpdateGroupReq.newBuilder()
      .setGroupId(groupId == null ? 0 : Long.parseLong(groupId));
    if (description != null) {
      b.setDescription(description);
    }
    return reqOf(operatorId, b);
  }

  private static ImMessage reqOf(long operatorId, GroupMgmtProto.UpdateGroupReq.Builder b) {
    Map<String, String> headers = new HashMap<>();
    headers.put("userId", String.valueOf(operatorId));
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER).version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0).cmd(CMD_GROUP_UPDATE_REQ_VALUE).messageId("m-1")
      .body(b.build().toByteArray()).varHeaders(headers).build();
  }

  private static int codeOf(ImMessage resp) throws Exception {
    return GroupMgmtProto.UpdateGroupResp.parseFrom(resp.getBody()).getCode();
  }

  private ImMessage call(Future<ImMessage> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("群主改名：落库裁剪后的群名，INFO_UPDATED 携带新名推给全体成员")
  void ownerRenames() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2), member(MEMBER_ID, 0));
    ImMessage resp = call(service.process(updateReq(OWNER_ID, String.valueOf(GROUP_ID), "  新群名  ")));
    assertEquals(0, codeOf(resp));
    assertEquals("新群名", repo.renamedName.get(), "落库的群名应去除首尾空白");
    assertEquals(2, pushes.size(), "通知应扇出到全体成员（含操作者自己的其他端）");
    for (PushEnvelope env : pushes) {
      assertEquals(CMD_GROUP_MEMBER_CHANGE_NOTIFY_VALUE, env.getCmd());
      GroupMgmtProto.GroupMemberChangeNotify notify =
        GroupMgmtProto.GroupMemberChangeNotify.parseFrom(env.getBody());
      assertEquals(GroupMgmtProto.GroupMemberChangeNotify.ChangeType.INFO_UPDATED, notify.getType());
      assertEquals("新群名", notify.getName());
      assertFalse(notify.hasDescription(), "只改群名时通知不应携带公告字段");
    }
  }

  @Test
  @DisplayName("管理员可改名")
  void adminRenames() throws Exception {
    repo.members = List.of(member(ADMIN_ID, 1), member(MEMBER_ID, 0));
    ImMessage resp = call(service.process(updateReq(ADMIN_ID, String.valueOf(GROUP_ID), "新群名")));
    assertEquals(0, codeOf(resp));
    assertEquals("新群名", repo.renamedName.get());
  }

  @Test
  @DisplayName("普通成员改名被拒且不落库")
  void memberDenied() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2), member(MEMBER_ID, 0));
    ImMessage resp = call(service.process(updateReq(MEMBER_ID, String.valueOf(GROUP_ID), "新群名")));
    assertTrue(codeOf(resp) != 0, "普通成员无权改名");
    assertEquals(null, repo.renamedName.get());
    assertEquals(0, pushes.size());
  }

  @Test
  @DisplayName("非成员改名被拒")
  void outsiderDenied() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2));
    ImMessage resp = call(service.process(updateReq(999L, String.valueOf(GROUP_ID), "新群名")));
    assertTrue(codeOf(resp) != 0);
    assertEquals(null, repo.renamedName.get());
  }

  @Test
  @DisplayName("群名为空被拒")
  void emptyNameRejected() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2));
    ImMessage resp = call(service.process(updateReq(OWNER_ID, String.valueOf(GROUP_ID), "   ")));
    assertTrue(codeOf(resp) != 0);
    assertEquals(null, repo.renamedName.get());
  }

  @Test
  @DisplayName("群主改公告：落库公告，INFO_UPDATED 携带新公告且不携带群名")
  void ownerUpdatesDescription() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2), member(MEMBER_ID, 0));
    ImMessage resp = call(service.process(updateDescReq(OWNER_ID, String.valueOf(GROUP_ID), "  今晚八点开会  ")));
    assertEquals(0, codeOf(resp));
    assertNull(repo.renamedName.get(), "只改公告时不应触碰群名");
    assertEquals("今晚八点开会", repo.updatedDesc.get(), "落库的公告应去除首尾空白");
    assertEquals(2, pushes.size());
    GroupMgmtProto.GroupMemberChangeNotify notify =
      GroupMgmtProto.GroupMemberChangeNotify.parseFrom(pushes.get(0).getBody());
    assertEquals(GroupMgmtProto.GroupMemberChangeNotify.ChangeType.INFO_UPDATED, notify.getType());
    assertEquals("今晚八点开会", notify.getDescription());
    assertFalse(notify.hasName(), "只改公告时通知不应携带群名");
  }

  @Test
  @DisplayName("清空公告：空串是合法值（与「未提供」区分）")
  void clearDescription() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2));
    ImMessage resp = call(service.process(updateDescReq(OWNER_ID, String.valueOf(GROUP_ID), "")));
    assertEquals(0, codeOf(resp));
    assertEquals("", repo.updatedDesc.get());
    GroupMgmtProto.GroupMemberChangeNotify notify =
      GroupMgmtProto.GroupMemberChangeNotify.parseFrom(pushes.get(0).getBody());
    assertEquals("", notify.getDescription());
  }

  @Test
  @DisplayName("普通成员改公告被拒")
  void memberCannotUpdateDescription() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2), member(MEMBER_ID, 0));
    ImMessage resp = call(service.process(updateDescReq(MEMBER_ID, String.valueOf(GROUP_ID), "越权公告")));
    assertTrue(codeOf(resp) != 0);
    assertNull(repo.updatedDesc.get());
    assertEquals(0, pushes.size());
  }

  @Test
  @DisplayName("name 与 description 都不提供 → 400")
  void nothingToUpdate() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2));
    ImMessage resp = call(service.process(updateDescReq(OWNER_ID, String.valueOf(GROUP_ID), null)));
    assertTrue(codeOf(resp) != 0);
    assertEquals(0, pushes.size());
  }

  @Test
  @DisplayName("群不存在（改名未生效）返回错误且不推送")
  void groupMissing() throws Exception {
    repo.members = List.of(member(OWNER_ID, 2));
    repo.renameRows = 0;
    ImMessage resp = call(service.process(updateReq(OWNER_ID, String.valueOf(GROUP_ID), "新群名")));
    assertTrue(codeOf(resp) != 0);
    assertEquals(0, pushes.size());
  }

  /** 成员表可配、改名可捕获的群仓储替身 */
  private class FakeGroupRepo implements GroupRepository {
    List<GroupMemberRecord> members = List.of();
    final AtomicReference<String> renamedName = new AtomicReference<>(null);
    final AtomicReference<String> updatedDesc = new AtomicReference<>(null);
    int renameRows = 1;

    @Override public Future<Integer> updateGroupInfo(long groupId, String name, String description) {
      renamedName.set(name);
      updatedDesc.set(description);
      return Future.succeededFuture(renameRows);
    }
    @Override public Future<List<GroupMemberRecord>> findMembers(long groupId) {
      return Future.succeededFuture(members);
    }
    @Override public Future<GroupInfo> findById(long id) { return Future.succeededFuture(null); }
    @Override public Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward) { return Future.succeededFuture(List.of()); }
    @Override public Future<GroupMemberContext> getGroupMemberContext(long g, long u) {
      return Future.succeededFuture(new GroupMemberContext(true, "g", true, false));
    }
    @Override public Future<Void> createGroup(GroupInfo group) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<List<GroupInfo>> findGroupsByUserId(long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> addMember(long id, long groupId, long userId, int role, long now) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> removeMember(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Integer> transferOwnership(long groupId, long fromOwnerId, long toUserId) { return Future.succeededFuture(0); }
    @Override public Future<Integer> dissolveGroup(long groupId, long ownerId) { return Future.succeededFuture(0); }
    @Override public Future<Boolean> isMember(long groupId, long userId) { return Future.succeededFuture(true); }
    @Override public Future<Map<Long, Long>> findMemberReadStates(long groupId) { return Future.succeededFuture(Map.of()); }
    @Override public Future<Boolean> isMuted(long groupId, long userId) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> isFriend(long a, long b) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
    @Override public Future<Boolean> saveMessage(long id, long groupId, long sender, int msgType, String content, String ext, long seq, long createdAt, long clientMsgId) { return Future.succeededFuture(true); }
    @Override public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long sender, long clientMsgId) { return Future.succeededFuture(null); }
    @Override public Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq) { return Future.failedFuture(new UnsupportedOperationException()); }
  }
}
