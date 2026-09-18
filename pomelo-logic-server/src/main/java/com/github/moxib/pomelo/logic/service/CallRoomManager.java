package com.github.moxib.pomelo.logic.service;

import io.vertx.core.Future;

/**
 * LiveKit 房间生命周期管理 abstraction（CallService 依赖此接口，测试可替换）。
 */
public interface CallRoomManager {

  Future<Void> createRoom(String room, int emptyTimeoutSeconds, int maxParticipants);

  /** 尽力而为：实现方对失败不应阻塞通话收尾（empty_timeout 兜底） */
  Future<Void> deleteRoom(String room);
}
