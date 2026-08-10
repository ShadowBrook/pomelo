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

  /**
   * groupId 参数均为 im_group.id（BIGINT 内部主键）。
   * 对外部 NanoID groupId 的解析由 service 层通过 findByGroupId() 完成。
   */
  Future<List<GroupMemberRecord>> findMembers(long groupId);

  Future<Void> addMember(long id, long groupId, long userId, int role, long now);

  Future<Void> removeMember(long groupId, long userId);

  Future<Boolean> isMember(long groupId, long userId);

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
