package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.service.model.MessageRecord;
import io.vertx.core.Future;

import java.util.List;

/**
 * 消息持久化仓库接口（数据层）。
 * 当前由 PgMessageRepository 实现，未来可通过 EventBus/gRPC 远程调用。
 */
public interface MessageRepository {

  /** 保存单聊消息，返回 true=新插入，false=冲突已存在（幂等） */
  Future<Boolean> save(MessageRecord record);

  /** 按 messageId 更新单条消息状态 */
  Future<Void> updateStatus(long messageId, int newStatus);

  /** 批量更新消息状态，ACK 场景使用。使用 WHERE status < newStatus 防止降级 */
  Future<Void> batchUpdateStatus(List<Long> messageIds, int newStatus);

  /** 拉取待处理消息（status < 该值）。用于离线拉取 */
  Future<List<MessageRecord>> pullPending(String userId, long sinceSeq, int limit);

  /** 按 messageId 查询消息，用于 ACK 时查找 senderId */
  Future<MessageRecord> findById(long messageId);

  /** 按 messageId 列表批量查询 senderId */
  Future<List<MessageRecord>> findByIds(List<Long> messageIds);
}
