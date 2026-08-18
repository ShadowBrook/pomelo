package com.github.moxib.pomelo.seqsvr.proto;

/**
 * AllocManager 状态机
 */
public enum AllocState {
  NONE(0),
  WAIT_ROUTE_TABLE(1),
  WAIT_LOAD(2),
  WAIT_LOAD_SEQ(3),
  INITED(4),
  ERROR(5);

  private final int value;

  AllocState(int value) { this.value = value; }

  public int getValue() { return value; }
}
