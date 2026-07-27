package com.github.moxib.pomelo.gateway.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户会话注册表。
 * 以 userId (NanoID) 为主键，id (BIGINT) 作为辅助字段存储在 Session 中。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  /** userId (NanoID) → Session */
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
  /** connection → userId，用于断连快速清理 */
  private final ConcurrentMap<Connection, String> connectionToUserId = new ConcurrentHashMap<>();

  /** 注册上线 */
  public void register(String userId, long id, Connection connection, byte codecId,
                       String userName, String nickname) {
    register(userId, id, connection, codecId, userName, nickname, null);
  }

  /** 注册上线（含 token） */
  public void register(String userId, long id, Connection connection, byte codecId,
                       String userName, String nickname, String token) {
    Session old = sessions.put(userId, new Session(id, userId, connection, codecId, userName, nickname, token));
    if (old != null && old.connection != connection) {
      LOG.info("用户 {} (id={}) 已在其他设备登录，旧连接将被替换", userId, id);
      old.connection.close();
      connectionToUserId.remove(old.connection);
    }
    connectionToUserId.put(connection, userId);
    LOG.info("用户 {} (id={}) 上线 (codec={}), 当前在线: {}", userId, id, codecId, sessions.size());
  }

  /** 按 userId 注销下线 */
  public String unregisterByUserId(String userId) {
    Session removed = sessions.remove(userId);
    if (removed != null) {
      connectionToUserId.remove(removed.connection);
      LOG.info("用户 {} (id={}) 下线，当前在线: {}", userId, removed.id, sessions.size());
    }
    return userId;
  }

  /** 注销指定连接（断连时清理），返回被移除的 userId */
  public String unregisterByConnection(Connection connection) {
    String userId = connectionToUserId.remove(connection);
    if (userId != null) {
      Session removed = sessions.remove(userId);
      if (removed != null) {
        LOG.info("用户 {} (id={}) 断连下线，当前在线: {}", userId, removed.id, sessions.size());
        return userId;
      }
    }
    return null;
  }

  /** 按 userId 获取连接 */
  public Connection getConnectionByUserId(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.connection : null;
  }

  /** 按 userId 获取 codecId */
  public byte getCodecByUserId(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.codecId : 0;
  }

  /** 按 userId 获取数字 id (BIGINT)，不在线返回 0 */
  public long getId(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.id : 0L;
  }

  /** 按 userId 获取显示名 */
  public String getUserName(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.userName : null;
  }

  /** 按 userId 获取昵称 */
  public String getNickname(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.nickname : null;
  }

  /** 按 userId 获取 token */
  public String getTokenByUserId(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.token : null;
  }

  /** 按 connection 获取 token */
  public String getTokenByConnection(Connection connection) {
    String userId = connectionToUserId.get(connection);
    return userId != null ? getTokenByUserId(userId) : null;
  }

  /** 在线用户数 */
  public int size() {
    return sessions.size();
  }

  static class Session {
    final long id;
    final String userId;
    final Connection connection;
    final byte codecId;
    final String userName;
    final String nickname;
    final String token;

    Session(long id, String userId, Connection connection, byte codecId,
            String userName, String nickname, String token) {
      this.id = id;
      this.userId = userId;
      this.connection = connection;
      this.codecId = codecId;
      this.userName = userName;
      this.nickname = nickname;
      this.token = token;
    }
  }
}
