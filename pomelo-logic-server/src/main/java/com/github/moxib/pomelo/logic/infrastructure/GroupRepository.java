package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import io.vertx.core.Future;

import java.util.List;

public interface GroupRepository {

  /**
   * 发送消息路径专用：单次查询获取群上下文（群存在性 + 群名 + 成员身份 + 禁言状态）。
   * 替代 findById + isMember + isMuted 三次 DB 查询。
   */
  Future<GroupMemberContext> getGroupMemberContext(long groupId, long userId);

  Future<Void> createGroup(GroupInfo group);

  Future<GroupInfo> findById(long id);

  Future<List<GroupInfo>> findGroupsByUserId(long userId);

  Future<List<GroupMemberRecord>> findMembers(long groupId);

  Future<Void> addMember(long id, long groupId, long userId, int role, long now);

  Future<Void> removeMember(long groupId, long userId);

  Future<Boolean> isMember(long groupId, long userId);

  /**
   * 检查用户在群内是否被禁言。
   * 返回 true 表示 muted_until > 当前毫秒时间戳，处于禁言状态。
   */
  Future<Boolean> isMuted(long groupId, long userId);

  Future<Boolean> isFriend(long userId1, long userId2);

  Future<Void> updateLastReadSeq(long groupId, long userId, long seq);

  Future<Boolean> saveMessage(long id, long groupId, long senderNumericId,
                               int msgType, String content, long seq, long createdAt);

  Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward);

  /**
   * 查询群消息的已读用户列表。
   * 通过群成员 last_read_seq 是否 >= 消息 seq 来判断已读。
   */
  Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq);
}
