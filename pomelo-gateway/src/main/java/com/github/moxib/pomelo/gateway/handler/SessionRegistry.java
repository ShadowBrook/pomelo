package com.github.moxib.pomelo.gateway.handler;

import com.github.moxib.pomelo.config.SessionRouteTable;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户会话注册表（端型槽位制）。
 * 以 {@code userId:platform}（路由键）为主键——同端型互斥（后登录由 Dispatcher 踢先登录），
 * 跨端型共存（web/android 各占一个槽位，推送按端型投递）。
 * 用户资料（id/userName/nickname）在登录时由 gateway 验签 token 自行解析。
 */
public class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  private final Vertx vertx;

  /** 路由键 (userId:platform) → Session */
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
  /** connection → Session，用于断连快速清理与认证身份判定 */
  private final ConcurrentMap<Connection, Session> connectionToSession = new ConcurrentHashMap<>();
  /** connection → 认证截止定时器 id（仅未认证连接存在） */
  private final ConcurrentMap<Connection, Long> authDeadlines = new ConcurrentHashMap<>();

  public SessionRegistry(Vertx vertx) {
    this.vertx = vertx;
  }

  /**
   * 注册上线，返回该端型槽位上的旧会话（顶号时由调用方负责通知后关闭旧连接）。
   * 本方法只做簿记替换：取消旧会话心跳、解除旧连接的身份映射，不主动 close。
   */
  public Session register(String userId, String platform, long id, Connection connection,
                          String userName, String nickname, String token) {
    String normalized = SessionRouteTable.normalizePlatform(platform);
    String routeKey = SessionRouteTable.routeKey(userId, normalized);
    Session fresh = new Session(id, userId, normalized, connection, userName, nickname, token);
    Session old = sessions.put(routeKey, fresh);
    if (old != null) {
      cancelHeartbeatTimer(old);
      // 两参 remove：旧连接若已被顶号易主则不动新映射
      connectionToSession.remove(old.connection, old);
    }
    connectionToSession.put(connection, fresh);
    cancelAuthDeadline(connection);
    if (old != null && old.connection != connection) {
      LOG.info("用户 {} (id={}) 端型 {} 顶号，旧连接待踢下线, 当前在线: {}",
        userId, id, normalized, sessions.size());
    } else {
      LOG.info("用户 {} (id={}) 端型 {} 上线, 当前在线: {}", userId, id, normalized, sessions.size());
    }
    return old;
  }

  /**
   * 注销指定连接（断连时清理），返回被移除的会话。
   * 仅当该连接仍是会话持有者时才清理——顶号后旧连接迟到的断连事件
   * 不得误删新设备已注册的会话。
   */
  public Session unregisterByConnection(Connection connection) {
    cancelAuthDeadline(connection);
    Session session = connectionToSession.remove(connection);
    if (session == null) {
      return null;
    }
    String routeKey = SessionRouteTable.routeKey(session.userId, session.platform);
    // 仅当槽位仍由本会话持有时才移除
    if (sessions.remove(routeKey, session)) {
      cancelHeartbeatTimer(session);
      LOG.info("用户 {} (id={}) 端型 {} 断连下线, 当前在线: {}",
        session.userId, session.id, session.platform, sessions.size());
    }
    return session;
  }

  /** 按端型槽位获取会话 */
  public Session getSession(String userId, String platform) {
    return sessions.get(SessionRouteTable.routeKey(userId, platform));
  }

  /** 用户全部端型会话（推送扇出投递用） */
  public List<Session> getSessionsByUserId(String userId) {
    List<Session> result = new ArrayList<>();
    for (Session s : sessions.values()) {
      if (s.userId.equals(userId)) {
        result.add(s);
      }
    }
    return result;
  }

  /** 按 connection 获取已认证会话，未认证连接返回 null */
  public Session getSessionByConnection(Connection connection) {
    return connectionToSession.get(connection);
  }

  /** 按 connection 获取已认证 userId，未认证连接返回 null */
  public String getUserIdByConnection(Connection connection) {
    Session s = connectionToSession.get(connection);
    return s != null ? s.userId : null;
  }

  /** 在线会话数（全部端型合计） */
  public int size() {
    return sessions.size();
  }

  /** 本节点全部在线会话（供节点下线清理 session 路由使用） */
  public Set<Session> getOnlineSessions() {
    return Set.copyOf(sessions.values());
  }

  // ---- 认证截止 ----

  /**
   * 为刚接受的连接挂一次性认证截止定时器。
   * 连接建立后不发任何数据、也不完成 AUTH 的 socket 否则会永久占用 FD；
   * 认证成功（{@link #register}）或断连即取消。
   */
  public void startAuthDeadline(Connection connection, long timeoutMs) {
    if (timeoutMs <= 0) {
      return;
    }
    long timerId = vertx.setTimer(timeoutMs, id -> {
      authDeadlines.remove(connection);
      if (connectionToSession.containsKey(connection)) {
        return;
      }
      LOG.warn("连接认证超时 {}ms，断开: {}", timeoutMs, connection.remoteAddress());
      connection.close();
    });
    Long previous = authDeadlines.put(connection, timerId);
    if (previous != null) {
      vertx.cancelTimer(previous);
    }
  }

  private void cancelAuthDeadline(Connection connection) {
    Long timerId = authDeadlines.remove(connection);
    if (timerId != null) {
      vertx.cancelTimer(timerId);
    }
  }

  // ---- 心跳超时 ----

  /**
   * 启动心跳定时器（login 成功后调用）。
   * 超时后关闭连接，由 closeHandler 完成后续清理。
   */
  public void startHeartbeatTimer(Session session, long timeoutMs) {
    if (session == null) return;
    session.heartbeatTimerId.set(newTimeoutTimer(session, timeoutMs));
  }

  /**
   * 重置心跳定时器（收到任何客户端消息时调用）。
   * 取消旧定时器并创建新的。
   */
  public void resetHeartbeatTimer(Session session, long timeoutMs) {
    if (session == null) return;
    cancelHeartbeatTimer(session);
    session.heartbeatTimerId.set(newTimeoutTimer(session, timeoutMs));
  }

  /**
   * 取消 Session 上的一次性超时定时器。
   * 直接操作 Session 对象而非按路由键查表，避免顶号窗口内误取消新会话的定时器。
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
      LOG.warn("用户 {} 端型 {} 心跳超时 {}ms，断开连接",
        session.userId, session.platform, timeoutMs);
      session.connection.close();
    });
  }

  // ---- Session ----

  static class Session {

    final long id;
    final String userId;
    final String platform;
    final Connection connection;
    final String userName;
    final String nickname;
    final String token;
    final AtomicLong heartbeatTimerId;

    Session(long id, String userId, String platform, Connection connection,
            String userName, String nickname, String token) {
      this.id = id;
      this.userId = userId;
      this.platform = platform;
      this.connection = connection;
      this.userName = userName;
      this.nickname = nickname;
      this.token = token;
      this.heartbeatTimerId = new AtomicLong(-1);
    }

    String getUserId() { return userId; }

    String getPlatform() { return platform; }
  }
}
