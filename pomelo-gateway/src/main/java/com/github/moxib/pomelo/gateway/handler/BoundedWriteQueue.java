package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 有界写队列 — 消费慢于生产时缓冲少量写入，溢出则关闭连接。
 * <p>
 * Vert.x 默认对写队列不设上限，慢消费者会把消息无限缓冲在 Netty 写队列导致 OOM。
 * 这里在应用层提供有界缓冲：队列未满时直写；写队列满时挂 drainHandler 缓冲；
 * 缓冲超过 {@link #MAX_PENDING} 条判定为慢消费者，主动断连（客户端心跳重连后可拉取补齐）。
 * <p>
 * 线程模型：同一连接有两条写入路径——连接自身的响应写在该连接的事件循环上，
 * 而 push（logic → gateway.push.*）在注册 push consumer 的另一个事件循环上执行
 * （Dispatcher 与网关 Verticle 是不同部署，context 不同）。因此队列状态必须固定
 * 在连接所属的 {@code context} 上串行访问：跨 context 的写入通过 runOnContext 转投递。
 * 直接在调用线程读写会并发破坏 ArrayDeque、丢 drainHandler 导致连接后续写入永久滞留。
 */
final class BoundedWriteQueue {

  private static final Logger LOG = LoggerFactory.getLogger(BoundedWriteQueue.class);

  /** 缓冲写入条数上限，超过即判定为慢消费者 */
  static final int MAX_PENDING = 64;

  private final WriteStream<Buffer> stream;
  private final Runnable closeAction;
  private final Context context;
  private final Deque<Buffer> pending = new ArrayDeque<>();
  private boolean drainHooked;
  private boolean closed;

  BoundedWriteQueue(WriteStream<Buffer> stream, Runnable closeAction, Context context) {
    this.stream = stream;
    this.closeAction = closeAction;
    this.context = context;
  }

  Future<Void> write(Buffer buffer) {
    if (context == null || Vertx.currentContext() == context) {
      return writeOnContext(buffer);
    }
    Promise<Void> promise = Promise.promise();
    context.runOnContext(v -> writeOnContext(buffer).onComplete(promise));
    return promise.future();
  }

  private Future<Void> writeOnContext(Buffer buffer) {
    if (closed) {
      return Future.failedFuture(new IllegalStateException("connection closed"));
    }
    if (pending.isEmpty() && !stream.writeQueueFull()) {
      return stream.write(buffer);
    }
    if (pending.size() >= MAX_PENDING) {
      LOG.warn("慢消费者：已缓冲 {} 条写入仍持续积压，断开连接", pending.size());
      closed = true;
      closeAction.run();
      return Future.failedFuture(new IllegalStateException("slow consumer connection closed"));
    }
    pending.add(buffer);
    if (!drainHooked) {
      drainHooked = true;
      stream.drainHandler(v -> flush());
    }
    return Future.succeededFuture();
  }

  private void flush() {
    while (!pending.isEmpty() && !stream.writeQueueFull()) {
      stream.write(pending.poll());
    }
    if (pending.isEmpty()) {
      // 释放
      drainHooked = false;
    }
  }
}
