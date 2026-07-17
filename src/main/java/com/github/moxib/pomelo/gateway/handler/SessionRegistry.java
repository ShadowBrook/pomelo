package com.github.moxib.pomelo.gateway.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户会话注册表。
 * 维护 user_id → Connection 的在线映射，单例由 MessageDispatcher 持有并注入 Handler。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  private final ConcurrentMap<String, Connection> sessions = new ConcurrentHashMap<>();

  /** 注册上线 */
  public void register(String userId, Connection connection) {
    Connection old = sessions.put(userId, connection);
    if (old != null && old != connection) {
      LOG.info("用户 {} 已在其他设备登录，旧连接将被替换", userId);
      old.close();
    }
    LOG.info("用户 {} 上线，当前在线: {}", userId, sessions.size());
  }

  /** 注销下线 */
  public void unregister(String userId) {
    Connection removed = sessions.remove(userId);
    if (removed != null) {
      LOG.info("用户 {} 下线，当前在线: {}", userId, sessions.size());
    }
  }

  /** 注销指定连接（用于断连时按 Connection 清理） */
  public void unregisterByConnection(Connection connection) {
    sessions.values().removeIf(c -> c == connection);
  }

  /** 获取用户连接，不在线返回 null */
  public Connection getConnection(String userId) {
    return sessions.get(userId);
  }

  /** 是否在线 */
  public boolean isOnline(String userId) {
    return sessions.containsKey(userId);
  }

  /** 在线用户数 */
  public int size() {
    return sessions.size();
  }
}
