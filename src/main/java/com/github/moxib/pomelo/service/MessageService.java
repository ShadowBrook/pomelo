package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.service.model.C2CRespResult;
import com.github.moxib.pomelo.service.model.MessageRecord;
import io.vertx.core.Future;

import java.util.List;

/**
 * 消息业务逻辑层接口。
 * 所有用户标识均使用 im_user.id (BIGINT)。
 */
public interface MessageService {

  /**
   * 发送单聊消息：
   * 1. 校验 sender/recipient 合法性
   * 2. 生成 seq
   * 3. 持久化消息（status=0）
   * 4. 如果 recipient 在线，推送 C2CNotify
   * 5. 返回 C2CRespResult
   */
  Future<C2CRespResult> sendC2CMessage(C2CReqContext ctx);

  /**
   * 处理 ACK 确认：
   * 1. 批量更新消息状态
   * 2. 查询消息对应的 senderId
   * 3. 返回 AckNotifyContext 列表（含 senderUserId，供 Handler 推送 AckNotify）
   */
  Future<List<AckNotifyContext>> processAck(List<Long> messageIds, int ackType, long ackFromUserId, String ackFromUserIdStr);

  /**
   * 拉取离线消息（status < 2 的消息）。
   */
  Future<List<MessageRecord>> pullOfflineMessages(long userId, long sinceSeq, int limit);

  /**
   * 拉取会话历史消息（双向，按 seq 倒序）。
   */
  Future<List<MessageRecord>> pullConversationHistory(long userId, long peerId, long beforeSeq, int limit);
}
