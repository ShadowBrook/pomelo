package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import io.vertx.core.Future;

import java.util.List;
import java.util.Map;

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

  /**
   * 转让群主：原子校验原群主并更新 owner_id，同时调整双方角色（原群主→成员、新群主→群主）。
   * groupId 不存在或 owner_id 不匹配时返回 0。
   */
  Future<Integer> transferOwnership(long groupId, long fromOwnerId, long toUserId);

  /**
   * 解散群聊：仅当 owner_id 匹配时删除群与其全部成员关系（历史消息保留）。
   * 返回删除的成员关系行数；群不存在或 owner 不匹配时返回 0。
   */
  Future<Integer> dissolveGroup(long groupId, long ownerId);

  /**
   * 修改群名/群公告（null 表示该字段不改；空串公告表示清空）。
   * 返回 1 表示已更新，0 表示群不存在。
   */
  Future<Integer> updateGroupInfo(long groupId, String name, String description);

  Future<Boolean> isMember(long groupId, long userId);

  /**
   * 检查用户在群内是否被禁言。
   * 返回 true 表示 muted_until > 当前毫秒时间戳，处于禁言状态。
   */
  Future<Boolean> isMuted(long groupId, long userId);

  Future<Boolean> isFriend(long userId1, long userId2);

  Future<Void> updateLastReadSeq(long groupId, long userId, long seq);

  Future<Boolean> saveMessage(long id, long groupId, long senderNumericId,
                               int msgType, String content, String ext, long seq, long createdAt, long clientMsgId);

  /**
   * 按 (groupId, senderId, clientMsgId) 查询已有群消息，用于客户端重试幂等。
   */
  Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long senderId, long clientMsgId);

  Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward);

  /**
   * 全群成员的已读游标（user_id → last_read_seq）。
   * 一次查询覆盖全群，调用方据此本地计算任意 seq 的已读人数，避免按消息逐条查询。
   */
  Future<Map<Long, Long>> findMemberReadStates(long groupId);

  /**
   * 查询群消息的已读用户列表。
   * 通过群成员 last_read_seq 是否 >= 消息 seq 来判断已读。
   */
  Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq);
}
