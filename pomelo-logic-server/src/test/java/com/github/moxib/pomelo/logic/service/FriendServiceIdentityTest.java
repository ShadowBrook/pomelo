package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.common.ErrorCode;
import com.github.moxib.pomelo.common.ImMessage;
import com.github.moxib.pomelo.model.PushEnvelope;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.github.moxib.pomelo.proto.relation.RelationProto;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 好友操作的身份来源测试。
 * <p>
 * 身份只信 gateway 规范化后的 varHeader：body 里的 userId 由客户端自由填写，
 * 若被信任，任意认证用户就能冒充他人发起申请、接受申请或删除他人好友关系。
 */
@DisplayName("FriendService 身份可信边界测试")
class FriendServiceIdentityTest {

  private Vertx vertx;
  private RecordingPool pool;

  /** 攻击者（已认证连接的真实身份） */
  private static final long ATTACKER = 111L;
  /** 受害者（被写进 body 冒充的身份） */
  private static final long VICTIM = 999L;
  /** 第三方好友关系中的另一方 */
  private static final long OTHER = 888L;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    pool = new RecordingPool();
  }

  @AfterEach
  void tearDown() {
    if (vertx == null) {
      return;
    }
    CountDownLatch latch = new CountDownLatch(1);
    vertx.close().onComplete(ar -> latch.countDown());
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private FriendService service() {
    PushRouter noopPush = new PushRouter(vertx) {
      @Override
      public void push(PushEnvelope env) {
        // no-op
      }
    };
    return new FriendService(pool.proxy(), noopPush, 20, (msgType, content) -> content);
  }

  /** 构造 Protobuf 好友请求：body 带 userId，varHeader 是网关注入的认证身份 */
  private static ImMessage friendReq(int cmd, Long bodyUserId, long friendId, Long headerUserId) {
    RelationProto.FriendDeleteReq.Builder deleteBuilder = RelationProto.FriendDeleteReq.newBuilder()
      .setFriendId(friendId);
    Map<String, String> headers = new HashMap<>();
    byte[] body;
    if (cmd == CommonProto.Cmd.CMD_FRIEND_DELETE_REQ_VALUE) {
      if (bodyUserId != null) {
        deleteBuilder.setUserId(bodyUserId);
      }
      body = deleteBuilder.build().toByteArray();
    } else {
      RelationProto.FriendAcceptReq.Builder acceptBuilder = RelationProto.FriendAcceptReq.newBuilder()
        .setFriendId(friendId);
      if (bodyUserId != null) {
        acceptBuilder.setUserId(bodyUserId);
      }
      body = acceptBuilder.build().toByteArray();
    }
    if (headerUserId != null) {
      headers.put("userId", String.valueOf(headerUserId));
    }
    return ImMessage.builder()
      .magic(ImMessage.MAGIC_NUMBER)
      .version(ImMessage.WIRE_PROTOCOL_VERSION)
      .codecId((byte) 0)
      .cmd(cmd)
      .messageId("m-1")
      .body(body)
      .varHeaders(headers)
      .build();
  }

  private static ImMessage awaitResult(FriendService service, ImMessage req) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ImMessage> resp = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();
    service.process(req).onComplete(ar -> {
      if (ar.succeeded()) {
        resp.set(ar.result());
      } else {
        err.set(ar.cause());
      }
      done.countDown();
    });
    assertTrue(done.await(10, TimeUnit.SECONDS), "process 应完成");
    assertNull(err.get(), "process 失败: " + err.get());
    return resp.get();
  }

  @Test
  @DisplayName("body 中的 userId 无法冒充他人删除好友关系")
  void forgedBodyUserIdCannotDeleteOthersFriendship() throws Exception {
    // 攻击者 111 声称自己是 999，企图删除 999 与 888 的好友关系
    ImMessage req = friendReq(CommonProto.Cmd.CMD_FRIEND_DELETE_REQ_VALUE, VICTIM, OTHER, ATTACKER);

    awaitResult(service(), req);

    assertFalse(pool.params.isEmpty(), "应执行删除语句");
    assertTrue(pool.sqls.get(0).contains("DELETE FROM im_friend"),
      "首条语句应为删除好友关系: " + pool.sqls.get(0));
    Tuple params = pool.params.get(0);
    assertEquals(ATTACKER, params.getLong(0), "user_id 参数必须是认证身份，而非 body 冒充的 userId");
    assertEquals(OTHER, params.getLong(1), "friend_id 参数来自 body");
  }

  @Test
  @DisplayName("缺少认证身份头的好友操作被拒绝且不触库")
  void missingAuthHeaderIsRejected() throws Exception {
    ImMessage req = friendReq(CommonProto.Cmd.CMD_FRIEND_DELETE_REQ_VALUE, VICTIM, OTHER, null);

    ImMessage resp = awaitResult(service(), req);

    CommonProto.ErrorBody error = CommonProto.ErrorBody.parseFrom(resp.getBody());
    assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode(), "应返回未认证错误");
    assertTrue(pool.sqls.isEmpty(), "未认证请求不应触达数据库");
  }

  @Test
  @DisplayName("冒充他人身份接受申请会被识别为自操作并拒绝")
  void forgedAcceptBecomesSelfOperationAndIsRejected() throws Exception {
    // 攻击者 111 声称自己是 999（body），试图接受「999 → 111」的申请：
    // 身份改取 header 后等价于「111 接受 111 的申请」，应被参数校验拦下
    ImMessage req = friendReq(CommonProto.Cmd.CMD_FRIEND_ACCEPT_REQ_VALUE, VICTIM, ATTACKER, ATTACKER);

    ImMessage resp = awaitResult(service(), req);

    CommonProto.ErrorBody error = CommonProto.ErrorBody.parseFrom(resp.getBody());
    assertEquals(ErrorCode.BAD_REQUEST.getCode(), error.getCode(), "应作为非法参数被拒绝");
    assertTrue(pool.sqls.isEmpty(), "非法请求不应触达数据库");
  }

  /**
   * Pool 测试替身：记录 preparedQuery 的 SQL 与 execute 参数，统一返回预设 rowCount 与空结果集。
   * 未预期的方法直接抛 UnsupportedOperationException，避免测试掩盖实现新增的调用。
   */
  private static final class RecordingPool {

    final List<String> sqls = new ArrayList<>();
    final List<Tuple> params = new ArrayList<>();
    final AtomicInteger rowCount = new AtomicInteger(1);

    Pool proxy() {
      return (Pool) Proxy.newProxyInstance(Pool.class.getClassLoader(), new Class<?>[]{Pool.class},
        (proxy, method, args) -> {
          if ("preparedQuery".equals(method.getName())) {
            return preparedQuery((String) args[0]);
          }
          if ("close".equals(method.getName())) {
            return Future.succeededFuture();
          }
          throw new UnsupportedOperationException("未预期的 Pool 调用: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
      return (PreparedQuery<RowSet<Row>>) Proxy.newProxyInstance(PreparedQuery.class.getClassLoader(),
        new Class<?>[]{PreparedQuery.class}, (proxy, method, args) -> {
          if ("execute".equals(method.getName())) {
            sqls.add(sql);
            params.add((Tuple) args[0]);
            return Future.succeededFuture(rowSet());
          }
          throw new UnsupportedOperationException("未预期的 PreparedQuery 调用: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private RowSet<Row> rowSet() {
      return (RowSet<Row>) Proxy.newProxyInstance(RowSet.class.getClassLoader(),
        new Class<?>[]{RowSet.class}, (proxy, method, args) -> switch (method.getName()) {
          case "rowCount" -> rowCount.get();
          case "size" -> 0;
          case "iterator" -> Collections.<Row>emptyList().iterator();
          default -> throw new UnsupportedOperationException("未预期的 RowSet 调用: " + method.getName());
        });
    }
  }
}
