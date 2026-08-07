package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import io.vertx.core.Future;

import java.util.List;

public interface GroupRepository {

  Future<Void> createGroup(GroupInfo group);

  Future<GroupInfo> findById(long groupId);

  Future<List<GroupInfo>> findGroupsByUserId(long userId);

  Future<List<GroupMemberRecord>> findMembers(long groupId);

  /** userId 使用 im_user.id (BIGINT) */
  Future<Void> addMember(long id, long groupId, long userId, int role, long now);

  Future<Void> removeMember(long groupId, long userId);

  Future<Boolean> isMember(long groupId, long userId);

  Future<Void> updateLastReadSeq(long groupId, long userId, long seq);

  Future<Boolean> saveMessage(long id, long groupId, long senderNumericId,
                               int msgType, String content, long seq, long createdAt);

  Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward);
}
