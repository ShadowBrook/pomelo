package com.github.moxib.pomelo.gateway.handler;

import io.vertx.core.Future;
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
 * 仅在事件循环线程访问，无需加锁。
 */
final class BoundedWriteQueue {

  private static final Logger LOG = LoggerFactory.getLogger(BoundedWriteQueue.class);

  /** 缓冲写入条数上限，超过即判定慢消费者 */
  static final int MAX_PENDING = 64;

  private final WriteStream<Buffer> stream;
  private final Runnable closeAction;
  private final Deque<Buffer> pending = new ArrayDeque<>();
  private boolean drainHooked;
  private boolean closed;

  BoundedWriteQueue(WriteStream<Buffer> stream, Runnable closeAction) {
    this.stream = stream;
    this.closeAction = closeAction;
  }

  Future<Void> write(Buffer buffer) {
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
