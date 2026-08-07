package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import io.vertx.core.Future;

import java.util.List;

public interface GroupRepository {

  Future<Void> createGroup(GroupInfo group);

  Future<GroupInfo> findById(long id);

  Future<GroupInfo> findByGroupId(String groupId);

  Future<List<GroupInfo>> findGroupsByUserId(long userId);

  Future<List<GroupMemberRecord>> findMembers(String groupId);

  Future<Void> addMember(long id, String groupId, long userId, int role, long now);

  Future<Void> removeMember(String groupId, long userId);

  Future<Boolean> isMember(String groupId, long userId);

  Future<Boolean> isFriend(long userId1, long userId2);

  Future<Void> updateLastReadSeq(String groupId, long userId, long seq);

  Future<Boolean> saveMessage(long id, String groupId, long senderNumericId,
                               int msgType, String content, long seq, long createdAt);

  Future<List<GroupMsgWithSender>> pullMessages(String groupId, long cursor, int limit, boolean backward);
}
