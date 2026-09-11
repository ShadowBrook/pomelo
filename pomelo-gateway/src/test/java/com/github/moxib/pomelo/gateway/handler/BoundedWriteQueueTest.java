package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接写队列线程模型测试。
 * <p>
 * 连接响应写在 socket 自己的事件循环上，而 logic 推送在注册 push consumer 的
 * 另一个事件循环上写同一连接，两条路径会并发进入 {@link BoundedWriteQueue}。
 * 队列状态（ArrayDeque / drainHooked / closed）必须固定在连接所属 context 上访问。
 */
@ExtendWith(VertxExtension.class)
@DisplayName("BoundedWriteQueue 线程模型测试")
class BoundedWriteQueueTest {

  @Test
  @DisplayName("跨事件循环写入被归一到连接所属 context 并保持内容")
  void crossContextWriteIsMarshalledToOwningContext(Vertx vertx, VertxTestContext ctx) {
    RecordingWriteStream stream = new RecordingWriteStream();
    AtomicReference<Context> owningContext = new AtomicReference<>();
    Promise<BoundedWriteQueue> queuePromise = Promise.promise();

    // 连接所属 verticle：模拟 socket 处理器创建连接（拿到自己的 context）
    Future<String> ownerDeployed = vertx.deployVerticle(new VerticleBase() {
      @Override
      public Future<?> start() {
        owningContext.set(Vertx.currentContext());
        queuePromise.complete(new BoundedWriteQueue(stream, () -> { }, Vertx.currentContext()));
        return Future.succeededFuture();
      }
    });

    // 推送 verticle：另一个部署 = 另一个 context，模拟 logic 推送路径写入连接
    ownerDeployed
      .compose(id -> queuePromise.future())
      .compose(queue -> vertx.deployVerticle(new VerticleBase() {
        @Override
        public Future<?> start() {
          assertNotSame(owningContext.get(), Vertx.currentContext(),
            "推送方应是另一个 context（否则本测试不覆盖跨 context 场景）");
          queue.write(Buffer.buffer("push-notify"))
            .onSuccess(v -> ctx.verify(() -> {
              assertSame(owningContext.get(), stream.writeContext.get(),
                "写入必须发生在连接所属 context");
              assertEquals("push-notify", stream.lastBuffer.get().toString(),
                "跨 context 转投递不得丢失或改写内容");
              ctx.completeNow();
            }))
            .onFailure(ctx::failNow);
          return Future.succeededFuture();
        }
      }))
      .onFailure(ctx::failNow);
  }

  @Test
  @DisplayName("缓冲超过上限判定为慢消费者并断开连接")
  void slowConsumerIsClosedWhenBufferOverflows() {
    RecordingWriteStream stream = new RecordingWriteStream();
    // 写队列持续满：写入全部进入缓冲，drainHandler 不会触发
    stream.setFull(true);
    AtomicBoolean closed = new AtomicBoolean(false);
    BoundedWriteQueue queue = new BoundedWriteQueue(stream, () -> closed.set(true), null);

    for (int i = 0; i < BoundedWriteQueue.MAX_PENDING; i++) {
      assertTrue(queue.write(Buffer.buffer("m" + i)).succeeded(),
        "缓冲未满时写入应被接受: " + i);
    }
    assertFalse(closed.get(), "缓冲未达上限不应断连");

    Future<Void> overflow = queue.write(Buffer.buffer("overflow"));
    assertTrue(overflow.failed(), "超过缓冲上限应拒绝写入");
    assertTrue(closed.get(), "超过缓冲上限应断开连接");
    assertTrue(queue.write(Buffer.buffer("after")).failed(), "断连后写入应失败");
  }

  /** 记录写入上下文与内容的最小 WriteStream 替身 */
  private static final class RecordingWriteStream implements WriteStream<Buffer> {

    private final AtomicReference<Context> writeContext = new AtomicReference<>();
    private final AtomicReference<Buffer> lastBuffer = new AtomicReference<>();
    private volatile boolean full;

    @Override
    public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public Future<Void> write(Buffer data) {
      writeContext.set(Vertx.currentContext());
      lastBuffer.set(data);
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return full;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
      return this;
    }

    void setFull(boolean full) {
      this.full = full;
    }
  }
}
