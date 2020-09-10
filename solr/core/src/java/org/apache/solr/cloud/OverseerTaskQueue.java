/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import com.codahale.metrics.Timer;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.TimeOut;
import org.apache.solr.common.util.TimeSource;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ZkDistributedQueue} augmented with helper methods specific to the overseer task queues.
 * Methods specific to this subclass ignore superclass internal state and hit ZK directly.
 * This is inefficient!  But the API on this class is kind of muddy..
 */
public class OverseerTaskQueue extends ZkDistributedQueue {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String RESPONSE_PREFIX = "qnr-" ;

  private final LongAdder pendingResponses = new LongAdder();

  public OverseerTaskQueue(SolrZkClient zookeeper, String dir) {
    this(zookeeper, dir, new Stats());
  }

  public OverseerTaskQueue(SolrZkClient zookeeper, String dir, Stats stats) {
    super(zookeeper, dir, stats);
  }

  public void allowOverseerPendingTasksToComplete() {
    while (pendingResponses.sum() > 0) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        ParWork.propagateInterrupt(e);
        log.error("Interrupted while waiting for overseer queue to drain before shutdown!");
      }
    }
  }

  /**
   * Returns true if the queue contains a task with the specified async id.
   */
  public boolean containsTaskWithRequestId(String requestIdKey, String requestId)
      throws KeeperException, InterruptedException {

    List<String> childNames = zookeeper.getChildren(dir, null, true);
    stats.setQueueLength(childNames.size());
    for (String childName : childNames) {
      if (childName != null && childName.startsWith(PREFIX)) {
        try {
          byte[] data = zookeeper.getData(dir + "/" + childName, null, null);
          if (data != null) {
            ZkNodeProps message = ZkNodeProps.load(data);
            if (message.containsKey(requestIdKey)) {
              if (log.isDebugEnabled()) {
                log.debug("Looking for {}, found {}", message.get(requestIdKey), requestId);
              }
              if(message.get(requestIdKey).equals(requestId)) return true;
            }
          }
        } catch (KeeperException.NoNodeException e) {
          // Another client removed the node first, try next
        }
      }
    }

    return false;
  }

  /**
   * Remove the event and save the response into the other path.
   */
  public void remove(QueueEvent event) throws KeeperException,
      InterruptedException {
    Timer.Context time = stats.time(dir + "_remove_event");
    try {
      String path = event.getId();
      String responsePath = dir + "/" + RESPONSE_PREFIX
          + path.substring(path.lastIndexOf("-") + 1);

      try {
        zookeeper.setData(responsePath, event.getBytes(), false);
      } catch (KeeperException.NoNodeException ignored) {
        // this will often not exist or have been removed
        if (log.isDebugEnabled()) log.debug("Response ZK path: {} doesn't exist.", responsePath);
      }
      try {
        zookeeper.delete(path, -1);
      } catch (KeeperException.NoNodeException ignored) {
      }
    } finally {
      time.stop();
    }
  }

  /**
   * Watcher that blocks until a WatchedEvent occurs for a znode.
   */
  static final class LatchWatcher implements Watcher {

    private final Lock lock;
    private final Condition eventReceived;
    private final SolrZkClient zkClient;
    private volatile WatchedEvent event;
    private final Event.EventType latchEventType;

    private volatile boolean triggered = false;

    LatchWatcher(SolrZkClient zkClient) {
      this(null, zkClient);
    }

    LatchWatcher(Event.EventType eventType, SolrZkClient zkClient) {
      this.zkClient = zkClient;
      this.lock = new ReentrantLock();
      this.eventReceived = lock.newCondition();
      this.latchEventType = eventType;
    }


    @Override
    public void process(WatchedEvent event) {
      // session events are not change events, and do not remove the watcher
      if (Event.EventType.None.equals(event.getType())) {
        return;
      }
      // If latchEventType is not null, only fire if the type matches
      if (log.isDebugEnabled()) {
        log.debug("{} fired on path {} state {} latchEventType {}", event.getType(), event.getPath(), event.getState(), latchEventType);
      }
      if (latchEventType == null || event.getType() == latchEventType) {
        try {
          lock.lockInterruptibly();
        } catch (InterruptedException e) {
          ParWork.propagateInterrupt(e);
          return;
        }
        try {
          this.event = event;
          triggered = true;
          eventReceived.signalAll();
        } finally {
          lock.unlock();
        }
      }
    }

    public void await(long timeoutMs) throws InterruptedException {
      assert timeoutMs > 0;
      lock.lockInterruptibly();
      try {
        if (this.event != null) {
          return;
        }
        TimeOut timeout = new TimeOut(timeoutMs, TimeUnit.MILLISECONDS, TimeSource.NANO_TIME);
        while (event == null && !timeout.hasTimedOut()) {
          try {
            eventReceived.await(timeoutMs, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            ParWork.propagateInterrupt(e);
            throw e;
          }
        }
      } finally {
        lock.unlock();
      }
    }

    public WatchedEvent getWatchedEvent() {
      return event;
    }
  }

  /**
   * Inserts data into zookeeper.
   *
   * @return true if data was successfully added
   */
  private String createData(String path, byte[] data, CreateMode mode)
      throws KeeperException, InterruptedException {
    return zookeeper.create(path, data, mode, true);
  }

  /**
   * Offer the data and wait for the response
   *
   */
  public QueueEvent offer(byte[] data, long timeout) throws KeeperException,
      InterruptedException {
    Timer.Context time = stats.time(dir + "_offer");
    try {
      // Create and watch the response node before creating the request node;
      // otherwise we may miss the response.
      String watchID = createResponseNode();

      LatchWatcher watcher = new LatchWatcher(zookeeper);
      byte[] bytes = zookeeper.getData(watchID, watcher, null);

      // create the request node
      createRequestNode(data, watchID);

      pendingResponses.increment();
      if (bytes == null) {
        watcher.await(timeout);
        bytes = zookeeper.getData(watchID, null, null);
      }

      // create the event before deleting the node, otherwise we can get the deleted
      // event from the watcher.
      QueueEvent event =  new QueueEvent(watchID, bytes, watcher.getWatchedEvent());
      zookeeper.delete(watchID, -1);
      return event;
    } finally {
      time.stop();
      pendingResponses.decrement();
    }
  }

  void createRequestNode(byte[] data, String watchID) throws KeeperException, InterruptedException {
    createData(dir + "/" + PREFIX + watchID.substring(watchID.lastIndexOf("-") + 1),
        data, CreateMode.PERSISTENT);
  }

  String createResponseNode() throws KeeperException, InterruptedException {
    return createData(
            dir + "/" + RESPONSE_PREFIX,
            null, CreateMode.EPHEMERAL_SEQUENTIAL);
  }


  public List<QueueEvent> peekTopN(int n, Predicate<String> excludeSet, long waitMillis)
      throws KeeperException, InterruptedException {
    ArrayList<QueueEvent> topN = new ArrayList<>();

    if (log.isDebugEnabled()) log.debug("Peeking for top {} elements. ExcludeSet: {}", n, excludeSet);
    Timer.Context time;
    if (waitMillis == Long.MAX_VALUE) time = stats.time(dir + "_peekTopN_wait_forever");
    else time = stats.time(dir + "_peekTopN_wait" + waitMillis);

    try {
      for (Pair<String, byte[]> element : peekElements(n, waitMillis, child -> !excludeSet.test(dir + "/" + child))) {
        topN.add(new QueueEvent(dir + "/" + element.first(),
            element.second(), null));
      }
      printQueueEventsListElementIds(topN);
      return topN;
    } finally {
      time.stop();
    }
  }

  private static void printQueueEventsListElementIds(ArrayList<QueueEvent> topN) {
    if (log.isDebugEnabled() && !topN.isEmpty()) {
      StringBuilder sb = new StringBuilder("[");
      for (QueueEvent queueEvent : topN) {
        sb.append(queueEvent.getId()).append(", ");
      }
      sb.append("]");
      log.debug("Returning topN elements: {}", sb);
    }
  }


  /**
   *
   * Gets last element of the Queue without removing it.
   */
  public String getTailId() throws KeeperException, InterruptedException {
    // TODO: could we use getChildren here?  Unsure what freshness guarantee the caller needs.
    updateLock.lockInterruptibly();
    TreeSet<String> orderedChildren;
    try {
       orderedChildren = new TreeSet<>(knownChildren);
    } finally {
      updateLock.unlock();
    }
    for (String headNode : orderedChildren.descendingSet())
      if (headNode != null) {
        try {
          QueueEvent queueEvent = new QueueEvent(dir + "/" + headNode, zookeeper.getData(dir + "/" + headNode,
              null, null), null);
          return queueEvent.getId();
        } catch (KeeperException.NoNodeException e) {
          // Another client removed the node first, try next
        }
      }
    return null;
  }

  public static class QueueEvent {
    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((id == null) ? 0 : id.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      QueueEvent other = (QueueEvent) obj;
      if (id == null) {
        if (other.id != null) return false;
      } else if (!id.equals(other.id)) return false;
      return true;
    }

    private volatile WatchedEvent event = null;
    private volatile String id;
    private volatile  byte[] bytes;

    QueueEvent(String id, byte[] bytes, WatchedEvent event) {
      this.id = id;
      this.bytes = bytes;
      this.event = event;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }

    public void setBytes(byte[] bytes) {
      this.bytes = bytes;
    }

    public byte[] getBytes() {
      return bytes;
    }

    public WatchedEvent getWatchedEvent() {
      return event;
    }

  }
}
