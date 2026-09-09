package com.github.moxib.pomelo.logic.id;

import io.vertx.core.Future;

public interface IdGenerator {

  /**
   * Initializes Id generator params.
   *
   * @param value - initial value
   * @param allocationSize - values range allocation size
   * @return <code>true</code> if Id generator initialized
   *         <code>false</code> if Id generator already initialized
   */
  Future<Boolean> tryInit(long value, long allocationSize);

  /**
   * Returns next unique number and monotonically increased
   * Returns: number
   */
  Future<Long> nextId();
}
