package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户会话注册表。
 * 以 userId (NanoID) 为主键，id (BIGINT) 作为辅助字段存储在 Session 中。
 * 用户资料（id/userName/nickname）在登录时由 gateway 验签 token 自行解析。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  private final Vertx vertx;

  /** userId (NanoID) → Session */
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
  /** connection → userId，用于断连快速清理与认证身份判定 */
  private final ConcurrentMap<Connection, String> connectionToUserId = new ConcurrentHashMap<>();

  public SessionRegistry(Vertx vertx) {
    this.vertx = vertx;
  }

  /** 注册上线 */
  public void register(String userId, long id, Connection connection,
                       String userName, String nickname) {
    register(userId, id, connection, userName, nickname, null);
  }

  /** 注册上线（含 token） */
  public void register(String userId, long id, Connection connection,
                       String userName, String nickname, String token) {
    Session old = sessions.put(userId, new Session(id, userId, connection, userName, nickname, token));
    if (old != null) {
      cancelHeartbeatTimer(old);
      if (old.connection != connection) {
        LOG.info("用户 {} (id={}) 已在其他设备登录，旧连接将被替换", userId, id);
        old.connection.close();
        connectionToUserId.remove(old.connection);
      }
    }
    connectionToUserId.put(connection, userId);
    LOG.info("用户 {} (id={}) 上线, 当前在线: {}", userId, id, sessions.size());
  }

  /** 按 userId 注销下线，返回被移除的 userId；用户不在线返回 null */
  public String unregisterByUserId(String userId) {
    Session removed = sessions.remove(userId);
    if (removed == null) {
      return null;
    }
    connectionToUserId.remove(removed.connection);
    cancelHeartbeatTimer(removed);
    LOG.info("用户 {} (id={}) 下线，当前在线: {}", userId, removed.id, sessions.size());
    return userId;
  }

  /**
   * 注销指定连接（断连时清理），返回被移除的 userId。
   * 仅当该连接仍是会话持有者时才清理——顶号后旧连接迟到的断连事件
   * 不得误删新设备已注册的会话。
   */
  public String unregisterByConnection(Connection connection) {
    String userId = connectionToUserId.remove(connection);
    if (userId == null) {
      return null;
    }
    Session session = sessions.get(userId);
    if (session == null || session.connection != connection) {
      return null;
    }
    sessions.remove(userId, session);
    cancelHeartbeatTimer(session);
    LOG.info("用户 {} (id={}) 断连下线，当前在线: {}", userId, session.id, sessions.size());
    return userId;
  }

  /** 按 userId 获取连接 */
  public Connection getConnectionByUserId(String userId) {
    Session s = sessions.get(userId);
    return s != null ? s.connection : null;
  }

  /** 按 connection 获取已认证 userId，未认证连接返回 null */
  public String getUserIdByConnection(Connection connection) {
    return connectionToUserId.get(connection);
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

  /** 本节点所有在线 userId（供节点下线清理 session 路由使用） */
  public Set<String> getOnlineUserIds() {
    return sessions.keySet();
  }

  // ---- 心跳超时 ----

  /**
   * 启动心跳定时器（login 成功后调用）。
   * 超时后关闭连接，由 closeHandler 完成后续清理。
   */
  public void startHeartbeatTimer(String userId, long timeoutMs) {
    Session session = sessions.get(userId);
    if (session == null) return;
    session.heartbeatTimerId.set(newTimeoutTimer(session, timeoutMs));
  }

  /**
   * 重置心跳定时器（收到任何客户端消息时调用）。
   * 取消旧定时器并创建新的。
   */
  public void resetHeartbeatTimer(String userId, long timeoutMs) {
    Session session = sessions.get(userId);
    if (session == null) return;
    cancelHeartbeatTimer(session);
    session.heartbeatTimerId.set(newTimeoutTimer(session, timeoutMs));
  }

  /**
   * 取消 Session 上的一次性超时定时器。
   * 直接操作 Session 对象而非按 userId 查表，避免顶号窗口内误取消新会话的定时器。
   */
  private void cancelHeartbeatTimer(Session session) {
    long timerId = session.heartbeatTimerId.getAndSet(-1);
    if (timerId >= 0) {
      vertx.cancelTimer(timerId);
    }
  }

  /**
   * 创建一次性超时定时器。
   */
  private long newTimeoutTimer(Session session, long timeoutMs) {
    return vertx.setTimer(timeoutMs, id -> {
      LOG.warn("用户 {} 心跳超时 {}ms，断开连接", session.userId, timeoutMs);
      session.connection.close();
    });
  }

  // ---- Session ----

  static class Session {
    final long id;
    final String userId;
    final Connection connection;
    final String userName;
    final String nickname;
    final String token;
    final AtomicLong heartbeatTimerId;

    Session(long id, String userId, Connection connection,
            String userName, String nickname, String token) {
      this.id = id;
      this.userId = userId;
      this.connection = connection;
      this.userName = userName;
      this.nickname = nickname;
      this.token = token;
      this.heartbeatTimerId = new AtomicLong(-1);
    }
  }
}
