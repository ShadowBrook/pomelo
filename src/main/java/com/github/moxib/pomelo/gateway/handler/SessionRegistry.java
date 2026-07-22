package com.github.moxib.pomelo.gateway.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;

/**
 * 用户会话注册表。
 * 核心 Map 以 im_user.id (BIGINT) 作为主键，同时维护 userId (NanoID) → id 的反向映射。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  /** id → Session */
  private final ConcurrentMap<Long, Session> sessions = new ConcurrentHashMap<>();
  /** userId (NanoID) → id (BIGINT) 反向映射 */
  private final ConcurrentMap<String, Long> userIdToId = new ConcurrentHashMap<>();
  /** connection → id，用于断连快速清理 */
  private final ConcurrentMap<Connection, Long> connectionToId = new ConcurrentHashMap<>();

  /** 注册上线（含显示名） */
  public void register(String userId, long id, Connection connection, byte codecId, String userName, String nickname) {
    register(userId, id, connection, codecId, userName, nickname, null);
  }

  /** 注册上线（含显示名 + token） */
  public void register(String userId, long id, Connection connection, byte codecId, String userName, String nickname, String token) {
    Session old = sessions.put(id, new Session(id, userId, connection, codecId, userName, nickname, token));
    if (old != null && old.connection != connection) {
      LOG.info("用户 {} (id={}) 已在其他设备登录，旧连接将被替换", userId, id);
      old.connection.close();
      connectionToId.remove(old.connection);
    }
    userIdToId.put(userId, id);
    connectionToId.put(connection, id);
    LOG.info("用户 {} (id={}) 上线 (codec={}), 当前在线: {}", userId, id, codecId, sessions.size());
  }

  /** 按 userId 注销下线，返回被移除的 userId（可能为 null） */
  public String unregisterByUserId(String userId) {
    Long id = userIdToId.remove(userId);
    if (id == null) return null;
    Session removed = sessions.remove(id);
    if (removed != null) {
      connectionToId.remove(removed.connection);
      LOG.info("用户 {} (id={}) 下线，当前在线: {}", userId, id, sessions.size());
    }
    return userId;
  }

  /** 按 id 注销下线 */
  public long unregisterById(long id) {
    Session removed = sessions.remove(id);
    if (removed != null) {
      userIdToId.remove(removed.userId);
      connectionToId.remove(removed.connection);
      LOG.info("用户 {} (id={}) 下线，当前在线: {}", removed.userId, id, sessions.size());
    }
    return id;
  }

  /** 注销指定连接（用于断连时清理），返回被移除的 userId */
  public String unregisterByConnection(Connection connection) {
    Long id = connectionToId.remove(connection);
    if (id != null) {
      Session removed = sessions.remove(id);
      if (removed != null) {
        userIdToId.remove(removed.userId);
        LOG.info("用户 {} (id={}) 断连下线，当前在线: {}", removed.userId, id, sessions.size());
        return removed.userId;
      }
    }
    return null;
  }

  /** 按 id 获取连接，不在线返回 null */
  public Connection getConnection(long id) {
    Session s = sessions.get(id);
    return s != null ? s.connection : null;
  }

  /** 按 userId (NanoID) 获取连接，不在线返回 null */
  public Connection getConnectionByUserId(String userId) {
    Long id = userIdToId.get(userId);
    return id != null ? getConnection(id) : null;
  }

  /** 按 id 获取 codecId，不在线返回 0（默认 Protobuf） */
  public byte getCodec(long id) {
    Session s = sessions.get(id);
    return s != null ? s.codecId : 0;
  }

  /** 按 userId 获取 codecId */
  public byte getCodecByUserId(String userId) {
    Long id = userIdToId.get(userId);
    return id != null ? getCodec(id) : 0;
  }

  /** userId → id 查询，不在线返回 0 */
  public long getId(String userId) {
    Long id = userIdToId.get(userId);
    return id != null ? id : 0L;
  }

  /** id → userId 查询 */
  public String getUserId(long id) {
    Session s = sessions.get(id);
    return s != null ? s.userId : null;
  }

  /** 按 id 查询是否在线 */
  public boolean isOnline(long id) {
    return sessions.containsKey(id);
  }

  /** 在线用户数 */
  public int size() {
    return sessions.size();
  }

  /** 内部会话记录 */
  /** 获取用户 display name（如果在线） */
  public String getUserName(long id) {
    Session s = sessions.get(id);
    return s != null ? s.userName : null;
  }

  /** 获取用户昵称（如果在线） */
  public String getNickname(long id) {
    Session s = sessions.get(id);
    return s != null ? s.nickname : null;
  }

  /** 获取用户 token（如果在线），用于登出时撤销 */
  public String getToken(long id) {
    Session s = sessions.get(id);
    return s != null ? s.token : null;
  }

  /** 按 userId 获取 token */
  public String getTokenByUserId(String userId) {
    Long id = userIdToId.get(userId);
    return id != null ? getToken(id) : null;
  }

  /** 按 connection 获取 token */
  public String getTokenByConnection(Connection connection) {
    Long id = connectionToId.get(connection);
    return id != null ? getToken(id) : null;
  }

  static class Session {
    final long id;
    final String userId;
    final Connection connection;
    final byte codecId;
    final String userName;
    final String nickname;
    final String token;

    Session(long id, String userId, Connection connection, byte codecId, String userName, String nickname, String token) {
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
