package com.github.moxib.pomelo.gateway;

import com.github.moxib.pomelo.model.PushEnvelope;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.github.moxib.pomelo.proto.common.CommonProto.Cmd.CMD_PONG_VALUE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus 通讯链路验证测试。
 */
@ExtendWith(VertxExtension.class)
class EventBusIntegrationTest {

  @Test
  void testBasicEventBusRequestReply(Vertx vertx, VertxTestContext ctx) {
    // 最简验证：String 消息的 request/reply
    vertx.eventBus().consumer("test.addr", msg -> {
      String body = (String) msg.body();
      msg.reply("REPLY:" + body);
    });

    vertx.eventBus().<String>request("test.addr", "hello")
      .onSuccess(reply -> {
        ctx.verify(() -> {
          assertEquals("REPLY:hello", reply.body());
          ctx.completeNow();
        });
      })
      .onFailure(ctx::failNow);
  }

  @Test
  void testPushEnvelopeJsonRoundTrip(Vertx vertx, VertxTestContext ctx) {
    PushEnvelope original = new PushEnvelope("user-abc", CMD_PONG_VALUE, new byte[]{1, 2, 3}, (byte) 0);
    JsonObject json = JsonObject.mapFrom(original);
    PushEnvelope restored = json.mapTo(PushEnvelope.class);

    assertEquals(original.getTargetUserId(), restored.getTargetUserId());
    assertEquals(original.getCmd(), restored.getCmd());
    assertEquals(original.getCodecId(), restored.getCodecId());
    assertArrayEquals(original.getBody(), restored.getBody());
    ctx.completeNow();
  }
}
