/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.manager;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.core.util.time.SteadyTime;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

/**
 * Keep a persistent roughly monotone view of how long a manager has been overseeing this cluster.
 * <p>
 * Example of how ManagerTime is expected to work:
 * <ul>
 * <li>The first Manager process (MP1) starts in an accumulo cluster</li>
 * <li>ManagerTime.getTime() is called in MP1 after it has been alive for 30 secs and returns 30
 * secs</li>
 * <li>ManagerTime.getTime() is called in MP1 after it has been alive for 90 secs and returns 90
 * secs</li>
 * <li>MP1 dies after being alive for 120 seconds</li>
 * <li>After 300 seconds the second manager process starts</li>
 * <li>ManagerTime.getTime() is called in MP2 after it has been alive for 15 secs and returns 120+15
 * secs. The 120 was how long MP1 was alive.</li>
 * <li>ManagerTime.getTime() is called in MP2 after it has been alive for 45 secs and returns 120+45
 * secs.</li>
 * <li>MP2 dies after being alive for 90 seconds.</li>
 * <li>After 600 seconds the second manager process starts</li>
 * <li>ManagerTime.getTime() is called in MP3 after it has been alive for 10 secs and returns
 * 120+90+10 secs. The 120 and 90 are the sums of the previous manager lifetimes.</li>
 * </ul>
 */
public class ManagerTime {
  private static final Logger log = LoggerFactory.getLogger(ManagerTime.class);

  private ZooReaderWriter zk;
  private final AtomicReference<Manager> manager;

  /**
   * Difference between time stored in ZooKeeper and System.nanoTime() when we last read from
   * ZooKeeper. This offset may be negative or positive (depending on if the current nanoTime of the
   * system is negative or positive) and is represented as a Duration to make computing future
   * updates to the skewAmount and SteadyTime simpler.
   * <p>
   * Example where the skewAmount would be negative:
   * <ul>
   * <li>There's an existing persisted SteadyTime duration stored in Zookeeper from the total
   * previous manager runs of 1,000,000</li>
   * <li>Manager starts up and reads the previous value and the gets the current nano time which is
   * 2,000,000</li>
   * <li>The skew gets computed as the previous steady time duration minus the current time, so that
   * becomes: 1,000,000 - 2,000,000 = -1,000,000 resulting in the skew value being negative 1
   * million in this case</li>
   * <li>When reading the current SteadyTime from the API, a new SteadyTime is computed by adding
   * the current nano time plus the skew. So let's say 100,000 ns have elapsed since the start, so
   * the current time is now 2,100,000. This results in:(-1,000,000) + 2,100,000 = 1,100,000. You
   * end up with 1.1 million as a SteadyTime value that is the current elapsed time of 100,000 for
   * the current manager run plus the previous SteadyTime of 1 million that was read on start.</li>
   * </ul>
   *
   * Example where the skewAmount would be positive:
   * <ul>
   * <li>The current persisted value from previous runs is 1,000,000</li>
   * <li>Manager starts up gets the current nano time which is -2,000,000</li>
   * <li>The skew gets computed as: 1,000,000 - (-2,000,000) = 3,000,000 resulting in the skew value
   * being positive 3 million in this case</li>
   * <li>When reading the current SteadyTime from the API, a new SteadyTime is computed by adding
   * the current nano time plus the skew. So let's say 100,000 ns have elapsed since the start, so
   * the current time is now -1,900,000. This results in: (3,000,000) + (-1,900,000) = 1,100,000.
   * You end up with 1.1 million as a SteadyTime value that is the current elapsed time of 100,000
   * for the current manager run plus the previous SteadyTime of 1 million that was read on
   * start.</li>
   * </ul>
   */
  private AtomicReference<Duration> skewAmount;

  ManagerTime() {
    this.manager = new AtomicReference<>(null);
  }

  // Once it's set call constructor
  public void setManager(Manager manager) throws IOException {
    Objects.requireNonNull(manager);
    if (this.manager.compareAndSet(null, manager)) {
      // I don't want this to throw IOException?
      initializeManagerTime();
    } else if (this.manager.get() != manager) {
      throw new IllegalStateException("Attempted to set different manager object");
    }
  }

  private Manager getManager() {
    // fail fast if not yet set
    return Objects.requireNonNull(manager.get());
  }

  private void initializeManagerTime() throws IOException {
    this.zk = getManager().getContext().getZooSession().asReaderWriter();
    try {
      zk.putPersistentData(Constants.ZMANAGER_TICK, "0".getBytes(UTF_8), NodeExistsPolicy.SKIP);
      skewAmount = new AtomicReference<>(updateSkew(getZkTime()));
    } catch (Exception ex) {
      throw new IOException("Error updating manager time", ex);
    }

    ThreadPools.watchCriticalScheduledTask(getManager().getContext().getScheduledExecutor()
        .scheduleWithFixedDelay(Threads.createNamedRunnable("Manager time keeper", this::run), 0,
            SECONDS.toMillis(10), MILLISECONDS));
  }

  /**
   * How long has this cluster had a Manager?
   *
   * @return Approximate total duration this cluster has had a Manager
   */
  public SteadyTime getTime() {
    return fromSkew(skewAmount.get());
  }

  public void run() {
    switch (getManager().getManagerState()) {
      // If we don't have the lock, periodically re-read the value in ZooKeeper, in case there's
      // another manager we're
      // shadowing for.
      case INITIAL:
      case STOP:
        try {
          skewAmount.set(updateSkew(getZkTime()));
        } catch (Exception ex) {
          if (log.isDebugEnabled()) {
            log.debug("Failed to retrieve manager tick time", ex);
          }
        }
        break;
      // If we do have the lock, periodically write our clock to ZooKeeper.
      case HAVE_LOCK:
      case SAFE_MODE:
      case NORMAL:
      case UNLOAD_METADATA_TABLETS:
      case UNLOAD_ROOT_TABLET:
        try {
          zk.putPersistentData(Constants.ZMANAGER_TICK, serialize(fromSkew(skewAmount.get())),
              NodeExistsPolicy.OVERWRITE);
        } catch (Exception ex) {
          if (log.isDebugEnabled()) {
            log.debug("Failed to update manager tick time", ex);
          }
        }
    }
  }

  private SteadyTime getZkTime() throws InterruptedException, KeeperException {
    return deserialize(zk.getData(Constants.ZMANAGER_TICK));
  }

  /**
   * Creates a new skewAmount from an existing SteadyTime steadyTime - System.nanoTime()
   *
   * @param steadyTime existing steadyTime
   * @return Updated skew
   */
  @VisibleForTesting
  static Duration updateSkew(SteadyTime steadyTime) {
    return updateSkew(steadyTime, System.nanoTime());
  }

  /**
   * Creates a new skewAmount from an existing SteadyTime by subtracting the given time value
   *
   * @param steadyTime existing steadyTime
   * @param time time to subtract to update skew
   * @return Updated skew
   */
  @VisibleForTesting
  static Duration updateSkew(SteadyTime steadyTime, long time) {
    return Duration.ofNanos(steadyTime.getNanos() - time);
  }

  /**
   * Create a new SteadyTime from a skewAmount using System.nanoTime() + skewAmount
   *
   * @param skewAmount the skew amount to add
   * @return A SteadyTime that has been skewed by the given skewAmount
   */
  @VisibleForTesting
  static SteadyTime fromSkew(Duration skewAmount) {
    return fromSkew(System.nanoTime(), skewAmount);
  }

  /**
   * Create a new SteadyTime from a given time in ns and skewAmount using time + skewAmount
   *
   * @param time time to add the skew amount to
   * @param skewAmount the skew amount to add
   * @return A SteadyTime that has been skewed by the given skewAmount
   */
  @VisibleForTesting
  static SteadyTime fromSkew(long time, Duration skewAmount) {
    return SteadyTime.from(skewAmount.plusNanos(time));
  }

  static SteadyTime deserialize(byte[] steadyTime) {
    return SteadyTime.from(Long.parseLong(new String(steadyTime, UTF_8)), TimeUnit.NANOSECONDS);
  }

  static byte[] serialize(SteadyTime steadyTime) {
    return Long.toString(steadyTime.getNanos()).getBytes(UTF_8);
  }
}
