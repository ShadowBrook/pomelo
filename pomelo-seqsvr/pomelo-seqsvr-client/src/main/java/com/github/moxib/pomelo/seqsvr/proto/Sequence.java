package com.github.moxib.pomelo.seqsvr.proto;

/**
 * 序列号分配结果
 */
public class Sequence {

  private long seq;
  private Router router;

  public Sequence() {}

  public Sequence(long seq) {
    this.seq = seq;
  }

  public Sequence(long seq, Router router) {
    this.seq = seq;
    this.router = router;
  }

  public long getSeq() { return seq; }
  public void setSeq(long seq) { this.seq = seq; }

  /** 可选：仅在客户端路由表过期时返回 */
  public Router getRouter() { return router; }
  public void setRouter(Router router) { this.router = router; }

  public boolean hasRouter() { return router != null; }
}
