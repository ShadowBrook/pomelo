package com.github.moxib.pomelo.service;

import com.github.moxib.pomelo.service.model.AckNotifyContext;
import com.github.moxib.pomelo.service.model.C2CReqContext;
import com.github.moxib.pomelo.service.model.C2CRespResult;
import com.github.moxib.pomelo.service.model.MessageRecord;
import io.vertx.core.Future;

import java.util.List;

/**
 * 消息业务逻辑层接口。
 * 当前由 MessageServiceImpl 同进程实现，未来可通过 EventBus/gRPC 远程调用。
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
   * 3. 返回 AckNotifyContext 列表（供 Handler 推送 AckNotify）
   */
  Future<List<AckNotifyContext>> processAck(List<Long> messageIds, int ackType, String ackFromUserId);

  /**
   * 拉取离线消息（status < 2 的消息）。
   */
  Future<List<MessageRecord>> pullOfflineMessages(String userId, long sinceSeq, int limit);
}
