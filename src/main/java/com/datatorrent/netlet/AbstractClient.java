/*
 * Copyright (c) 2013 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.netlet;

import com.datatorrent.netlet.Listener.ClientListener;
import com.datatorrent.netlet.NetletThrowable.NetletRuntimeException;
import com.datatorrent.netlet.util.CircularBuffer;
import com.datatorrent.netlet.util.Slice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * <p>
 * Abstract AbstractClient class.</p>
 *
 * @since 1.0.0
 */
public abstract class AbstractClient implements ClientListener
{
  private static final int THROWABLES_COLLECTION_SIZE = 4;
  public static final int MAX_SENDBUFFER_SIZE;
  public static final int MAX_SENDBUFFER_BYTES;
  private static final long WRITE_COUNT_UPDATE_INTERVAL;

  protected final CircularBuffer<NetletThrowable> throwables;
  protected final CircularBuffer<CircularBuffer<Slice>> bufferOfBuffers;
  protected final CircularBuffer<Slice> freeBuffer;
  protected CircularBuffer<Slice> sendBuffer4Offers, sendBuffer4Polls;
  // Using two counters for measuring pending send data to avoid using explicit locks of any kind
  protected long sendBufferBytes, currWriteBufferBytes;
  // Only periodically update write counter that is visible to the send thread
  protected volatile long writeBufferBytes;
  protected final ByteBuffer writeBuffer;
  protected boolean write = true;
  protected SelectionKey key;

  // Max send buffer byteswriteBufferBytes
  // This overrides the default specified from property
  private int maxSendBufferBytes;
  private long writeCountUpdateInterval;

  private long lastWriteUpdateTS;

  public boolean isConnected()
  {
    return key.isValid() && ((SocketChannel)key.channel()).isConnected();
  }

  public AbstractClient(int writeBufferSize, int sendBufferSize)
  {
    this(ByteBuffer.allocateDirect(writeBufferSize), sendBufferSize);
  }

  public AbstractClient(int sendBufferSize)
  {
    this(8 * 1 * 1024, sendBufferSize);
  }

  public AbstractClient()
  {
    this(8 * 1 * 1024, 1024);
  }

  public AbstractClient(ByteBuffer writeBuffer, int sendBufferSize)
  {
    int i = 1;
    int n = 1;
    do {
      n *= 2;
      i++;
    }
    while (n != MAX_SENDBUFFER_SIZE);
    bufferOfBuffers = new CircularBuffer<CircularBuffer<Slice>>(i);

    this.throwables = new CircularBuffer<NetletThrowable>(THROWABLES_COLLECTION_SIZE);
    this.writeBuffer = writeBuffer;
    if (sendBufferSize == 0) {
      sendBufferSize = 1024;
    }
    else if (sendBufferSize % 1024 > 0) {
      sendBufferSize += 1024 - (sendBufferSize % 1024);
    }
    sendBuffer4Polls = sendBuffer4Offers = new CircularBuffer<Slice>(sendBufferSize, 10);
    freeBuffer = new CircularBuffer<Slice>(sendBufferSize, 10);
    maxSendBufferBytes = MAX_SENDBUFFER_BYTES;
    writeCountUpdateInterval = WRITE_COUNT_UPDATE_INTERVAL;
  }

  @Override
  public void registered(SelectionKey key)
  {
    this.key = key;
  }

  @Override
  public void connected()
  {
    write = false;
  }

  @Override
  public void disconnected()
  {
    write = true;
  }

  @Override
  public final void read() throws IOException
  {
    SocketChannel channel = (SocketChannel)key.channel();
    int read;
    if ((read = channel.read(buffer())) > 0) {
      this.read(read);
    }
    else if (read == -1) {
      try {
        channel.close();
      }
      finally {
        disconnected();
        unregistered(key);
        key.attach(Listener.NOOP_CLIENT_LISTENER);
      }
    }
    else {
      logger.debug("{} read 0 bytes", this);
    }
  }

  /**
   * @since 1.2.0
   */
  public boolean isReadSuspended()
  {
    return (key.interestOps() & SelectionKey.OP_READ) == 0;
  }

  /**
   * @since 1.2.0
   */
  public boolean suspendReadIfResumed()
  {
    final int interestOps = key.interestOps();
    if ((interestOps & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
      logger.debug("Suspending read on key {} with attachment {}", key, key.attachment());
      key.interestOps(interestOps & ~SelectionKey.OP_READ);
      return true;
    } else {
      return false;
    }
  }

  /**
   * @since 1.2.0
   */
  public boolean resumeReadIfSuspended()
  {
    final int interestOps = key.interestOps();
    if ((interestOps & SelectionKey.OP_READ) == 0) {
      logger.debug("Resuming read on key {} with attachment {}", key, key.attachment());
      key.interestOps(interestOps | SelectionKey.OP_READ);
      key.selector().wakeup();
      return true;
    } else {
      return false;
    }
  }

  /**
   * @deprecated As of release 1.2.0, replaced by {@link #suspendReadIfResumed()}
   */
  @Deprecated
  public void suspendRead()
  {
    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
  }

  /**
   * @deprecated As of release 1.2.0, replaced by {@link #resumeReadIfSuspended()}
   */
  @Deprecated
  public void resumeRead()
  {
    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
    key.selector().wakeup();
  }

  @Override
  public final void write() throws IOException
  {
    /*
     * at first when we enter this function, our buffer is in fill mode.
     */
    int remaining, size, curr;
    if ((size = sendBuffer4Polls.size()) > 0 && (remaining = writeBuffer.remaining()) > 0) {
      curr = remaining;
      do {
        Slice f = sendBuffer4Polls.peekUnsafe();
        if (remaining < f.length) {
          writeBuffer.put(f.buffer, f.offset, remaining);
          f.offset += remaining;
          f.length -= remaining;
          break;
        }
        else {
          writeBuffer.put(f.buffer, f.offset, f.length);
          remaining -= f.length;
          freeBuffer.offer(sendBuffer4Polls.pollUnsafe());
        }
      }
      while (--size > 0);
      if (maxSendBufferBytes != Integer.MAX_VALUE) {
        currWriteBufferBytes += (curr - writeBuffer.remaining());
        long currTS = System.currentTimeMillis();
        if ((currTS - lastWriteUpdateTS) >= writeCountUpdateInterval) {
          writeBufferBytes = currWriteBufferBytes;
          lastWriteUpdateTS = currTS;
        }
      }
    }

    /*
     * switch to the read mode!
     */
    writeBuffer.flip();

    SocketChannel channel = (SocketChannel)key.channel();
    while ((remaining = writeBuffer.remaining()) > 0) {
      remaining -= channel.write(writeBuffer);
      if (remaining > 0) {
        /*
         * switch back to the fill mode.
         */
        writeBuffer.compact();
        return;
      }
      else if (size > 0) {
        /*
         * switch back to the write mode.
         */
        writeBuffer.clear();

        curr = remaining = writeBuffer.capacity();
        do {
          Slice f = sendBuffer4Polls.peekUnsafe();
          if (remaining < f.length) {
            writeBuffer.put(f.buffer, f.offset, remaining);
            f.offset += remaining;
            f.length -= remaining;
            break;
          }
          else {
            writeBuffer.put(f.buffer, f.offset, f.length);
            remaining -= f.length;
            freeBuffer.offer(sendBuffer4Polls.pollUnsafe());
          }
        }
        while (--size > 0);
        if (maxSendBufferBytes != Integer.MAX_VALUE) {
          writeBufferBytes += (curr - writeBuffer.remaining());
        }

        /*
         * switch to the read mode.
         */
        writeBuffer.flip();
      }
    }

    /*
     * switch back to fill mode.
     */
    writeBuffer.clear();
    synchronized (bufferOfBuffers) {
      if (sendBuffer4Polls.isEmpty()) {
        if (sendBuffer4Offers == sendBuffer4Polls) {
          key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
          write = false;
        }
        else if (bufferOfBuffers.isEmpty()) {
          sendBuffer4Polls = sendBuffer4Offers;
        }
        else {
          sendBuffer4Polls = bufferOfBuffers.pollUnsafe();
        }
      }
    }
  }

  public boolean send(byte[] array)
  {
    return send(array, 0, array.length);
  }

  public boolean send(byte[] array, int offset, int len)
  {
    // Don't perform send bytes calculation if this limit was not set
    if (maxSendBufferBytes != Integer.MAX_VALUE) {
      // Handle wrap over of long
      long pendingBytes = 0;
      if ((sendBufferBytes < 0) && (writeBufferBytes >= 0)) {
        pendingBytes = -(sendBufferBytes + writeBufferBytes);
      } else {
        pendingBytes = sendBufferBytes - writeBufferBytes;
      }

      // If we cross the limit then don't send the data
      if ((maxSendBufferBytes - pendingBytes) < len) {
        return false;
      }
    }

    Slice f;
    if (freeBuffer.isEmpty()) {
      f = new Slice(array, offset, len);
    }
    else {
      f = freeBuffer.pollUnsafe();
      f.buffer = array;
      f.offset = offset;
      f.length = len;
    }

    if (sendBuffer4Offers.offer(f)) {
      synchronized (bufferOfBuffers) {
        if (!write) {
          key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
          write = true;
          key.selector().wakeup();
        }
      }

      sendBufferBytes += len;
      return true;
    }

    if (!throwables.isEmpty()) {
      NetletThrowable.Util.throwRuntime(throwables.pollUnsafe());
    }

    if (sendBuffer4Offers.capacity() != MAX_SENDBUFFER_SIZE) {
      synchronized (bufferOfBuffers) {
        if (sendBuffer4Offers != sendBuffer4Polls) {
          bufferOfBuffers.add(sendBuffer4Offers);
        }

        sendBuffer4Offers = new CircularBuffer<Slice>(sendBuffer4Offers.capacity() << 1);
        sendBuffer4Offers.add(f);
        if (!write) {
          key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
          write = true;
          key.selector().wakeup();
        }

        sendBufferBytes += len;
        return true;
      }
    }

    return false;
  }

  @Override
  public void handleException(Exception cce, EventLoop el)
  {
    logger.debug("Collecting exception in {}", throwables.size(), cce);
    throwables.offer(NetletThrowable.Util.rewrap(cce, el));
  }

  public abstract ByteBuffer buffer();

  public abstract void read(int len);

  @Override
  public void unregistered(SelectionKey key)
  {
    synchronized (bufferOfBuffers) {
      final CircularBuffer<Slice> SEND_BUFFER = sendBuffer4Offers;
      sendBuffer4Offers = new CircularBuffer<Slice>(0)
      {
        @Override
        public boolean isEmpty()
        {
          return SEND_BUFFER.isEmpty();
        }

        @Override
        public boolean offer(Slice e)
        {
          throw new NetletRuntimeException(new UnsupportedOperationException("Client does not own the socket any longer!"), null);
        }

        @Override
        public int size()
        {
          return SEND_BUFFER.size();
        }

        @Override
        public Slice pollUnsafe()
        {
          return SEND_BUFFER.pollUnsafe();
        }

        @Override
        public Slice peekUnsafe()
        {
          return SEND_BUFFER.peekUnsafe();
        }

      };
    }
  }

  public int getMaxSendBufferBytes()
  {
    return maxSendBufferBytes;
  }

  public void setMaxSendBufferBytes(int maxSendBufferBytes)
  {
    this.maxSendBufferBytes = maxSendBufferBytes;
  }

  public long getWriteCountUpdateInterval() {
    return writeCountUpdateInterval;
  }

  public void setWriteCountUpdateInterval(long writeCountUpdateInterval) {
    this.writeCountUpdateInterval = writeCountUpdateInterval;
  }

  private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);

  /* implemented here since it requires access to logger. */
  static {
    int size = 32 * 1024;
    final String key = "NETLET.MAX_SENDBUFFER_SIZE";
    String property = System.getProperty(key);
    if (property != null) {
      try {
        size = Integer.parseInt(property);
        if (size <= 0) {
          throw new IllegalArgumentException(key + " needs to be a positive integer which is also power of 2.");
        }

        if ((size & (size - 1)) != 0) {
          size--;
          size |= size >> 1;
          size |= size >> 2;
          size |= size >> 4;
          size |= size >> 8;
          size |= size >> 16;
          size++;
          logger.warn("{} set to {} since {} is not power of 2.", key, size, property);
        }
      }
      catch (Exception exception) {
        logger.warn("{} set to {} since {} could not be parsed as an integer.", key, size, property, exception);
      }
    }
    MAX_SENDBUFFER_SIZE = size;
    int bytes = Integer.MAX_VALUE;
    property = System.getProperty("NETLET.MAX_SENDBUFFER_BYTES");
    if (property != null) {
      try {
        bytes = Integer.parseInt(property);
      }
      catch (Exception exception) {
        logger.warn("{} set to {} since {} could not be parsed as an integer.", "NETLET.MAX_SENDBUFFER_BYTES", bytes, property, exception);
      }
    }
    MAX_SENDBUFFER_BYTES = bytes;
    long interval = 30000;
    property = System.getProperty("NETLET.WRITE_COUNT_UPDATE_INTERVAL");
    if (property != null) {
      try {
        interval = Long.parseLong(property);
      }
      catch (Exception exception) {
        logger.warn("{} set to {} since {} could not be parsed as a long.", "NETLET.WRITE_COUNT_UPDATE_INTERVAL", interval, property, exception);
      }
    }
    WRITE_COUNT_UPDATE_INTERVAL = interval;
  }

}
