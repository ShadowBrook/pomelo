package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.MessageRecord;
import com.github.moxib.pomelo.logic.model.UserIdInfo;
import io.vertx.core.Future;

import java.util.List;
import java.util.Map;

/**
 * 消息持久化仓库接口（数据层）。
 * 所有用户标识均使用 im_user.id (BIGINT Snowflake)。
 */
public interface MessageRepository {

  /** 保存单聊消息，返回 true=新插入，false=冲突已存在（幂等） */
  Future<Boolean> save(MessageRecord record);

  /** 按 messageId 更新单条消息状态 */
  Future<Void> updateStatus(long messageId, int newStatus);

  /** 批量更新消息状态，ACK 场景使用。使用 WHERE status < newStatus 防止降级 */
  Future<Void> batchUpdateStatus(List<Long> messageIds, int newStatus);

  /** 拉取待处理消息（status < 2）。按接收方 im_user.id 查询 */
  Future<List<MessageRecord>> pullPending(long recipientId, long sinceSeq, int limit);

  /** 按 messageId 查询消息 */
  Future<MessageRecord> findById(long messageId);

  /** 按 messageId 列表批量查询 */
  Future<List<MessageRecord>> findByIds(List<Long> messageIds);

  /** 拉取会话历史消息（双向，按 conversation_id + created_at 倒序分页） */
  Future<List<MessageRecord>> pullConversation(String conversationId, long beforeTime, int limit);

  /** 按 im_user.id 列表批量查询用户信息，返回 id → UserIdInfo 映射（userId 为 Snowflake 字符串） */
  Future<Map<Long, UserIdInfo>> findUserIdsByIds(List<Long> ids);
}
