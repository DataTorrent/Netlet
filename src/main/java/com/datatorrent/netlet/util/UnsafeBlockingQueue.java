/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.netlet.util;

import java.util.concurrent.BlockingQueue;

/**
 * <p>UnsafeBlockingQueue interface.</p>
 *
 * @param <T> type of the objects in this queue.
 * @author Chetan Narsude <chetan@datatorrent.com>
 * @since 0.3.2
 */
public interface UnsafeBlockingQueue<T> extends BlockingQueue<T>
{
  /**
   * Retrieves and removes the head of this queue.
   *
   * This method should be called only when the callee knows that the head is present. It skips
   * the checks that poll does hence you may get unreliable results if you use it without checking
   * for the presence of the head first.
   *
   * @return the head of this queue.
   */
  public T pollUnsafe();

  public T peekUnsafe();
}
