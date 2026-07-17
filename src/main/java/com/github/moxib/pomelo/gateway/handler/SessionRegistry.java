package com.github.moxib.pomelo.gateway.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户会话注册表。
 * 维护 user_id → (Connection, codecId) 的在线映射。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

  /** 注册上线，记录 codecId */
  public void register(String userId, Connection connection, byte codecId) {
    Session old = sessions.put(userId, new Session(connection, codecId));
    if (old != null && old.connection != connection) {
      LOG.info("用户 {} 已在其他设备登录，旧连接将被替换", userId);
      old.connection.close();
    }
    LOG.info("用户 {} 上线 (codec={}), 当前在线: {}", userId, codecId, sessions.size());
  }

  /** 注销下线 */
  public void unregister(String userId) {
    Session removed = sessions.remove(userId);
    if (removed != null) {
      LOG.info("用户 {} 下线，当前在线: {}", userId, sessions.size());
    }
  }

  /** 注销指定连接（用于断连时清理） */
  public void unregisterByConnection(Connection connection) {
    sessions.values().removeIf(s -> s.connection == connection);
  }

  /** 获取用户连接，不在线返回 null */
  public Connection getConnection(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.connection : null;
  }

  /** 获取用户 codecId，不在线返回 0（默认 Protobuf） */
  public byte getCodec(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.codecId : 0;
  }

  /** 是否在线 */
  public boolean isOnline(String userId) {
    return sessions.containsKey(userId);
  }

  /** 在线用户数 */
  public int size() {
    return sessions.size();
  }

  /** 内部会话记录 */
  private static class Session {
    final Connection connection;
    final byte codecId;

    Session(Connection connection, byte codecId) {
      this.connection = connection;
      this.codecId = codecId;
    }
  }
}
