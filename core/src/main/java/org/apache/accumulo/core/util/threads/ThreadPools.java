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
package org.apache.accumulo.core.util.threads;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.ACCUMULO_POOL_PREFIX;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.COORDINATOR_RESERVATION_META_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.COORDINATOR_RESERVATION_ROOT_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.COORDINATOR_RESERVATION_USER_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.GC_DELETE_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.GC_WAL_DELETE_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.GENERAL_SERVER_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.MANAGER_STATUS_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.SCHED_FUTURE_CHECKER_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_ASSIGNMENT_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_CONDITIONAL_UPDATE_META_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_CONDITIONAL_UPDATE_ROOT_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_CONDITIONAL_UPDATE_USER_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_MIGRATIONS_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_MINOR_COMPACTOR_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_SUMMARY_PARTITION_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_SUMMARY_REMOTE_POOL;
import static org.apache.accumulo.core.util.threads.ThreadPoolNames.TSERVER_SUMMARY_RETRIEVAL_POOL;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.trace.TraceUtil;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

@SuppressFBWarnings(value = "RV_EXCEPTION_NOT_THROWN",
    justification = "Throwing Error for it to be caught by AccumuloUncaughtExceptionHandler")
public class ThreadPools {

  public static class ExecutionError extends Error {

    private static final long serialVersionUID = 1L;

    public ExecutionError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(ThreadPools.class);

  // the number of seconds before we allow a thread to terminate with non-use.
  public static final long DEFAULT_TIMEOUT_MILLISECS = MINUTES.toMillis(3);

  private static final ThreadPools SERVER_INSTANCE = new ThreadPools(Threads.UEH);

  public static final ThreadPools getServerThreadPools() {
    return SERVER_INSTANCE;
  }

  public static final ThreadPools getClientThreadPools(AccumuloConfiguration conf,
      UncaughtExceptionHandler ueh) {
    ThreadPools clientPools = new ThreadPools(ueh);
    if (conf.getBoolean(Property.GENERAL_MICROMETER_ENABLED) == false) {
      clientPools.disableThreadPoolMetrics();
    } else {
      clientPools.setMeterRegistry(Metrics.globalRegistry);
    }
    return clientPools;
  }

  private static final ThreadPoolExecutor SCHEDULED_FUTURE_CHECKER_POOL =
      getServerThreadPools().getPoolBuilder(SCHED_FUTURE_CHECKER_POOL).numCoreThreads(1).build();

  private static final ConcurrentLinkedQueue<ScheduledFuture<?>> CRITICAL_RUNNING_TASKS =
      new ConcurrentLinkedQueue<>();

  private static final ConcurrentLinkedQueue<ScheduledFuture<?>> NON_CRITICAL_RUNNING_TASKS =
      new ConcurrentLinkedQueue<>();

  private static final Runnable TASK_CHECKER = () -> {
    final List<ConcurrentLinkedQueue<ScheduledFuture<?>>> queues =
        List.of(CRITICAL_RUNNING_TASKS, NON_CRITICAL_RUNNING_TASKS);
    while (true) {
      queues.forEach(q -> {
        Iterator<ScheduledFuture<?>> tasks = q.iterator();
        while (tasks.hasNext()) {
          if (checkTaskFailed(tasks.next(), q)) {
            tasks.remove();
          }
        }
      });
      try {
        TimeUnit.MINUTES.sleep(1);
      } catch (InterruptedException ie) {
        // This thread was interrupted by something while sleeping. We don't want to exit
        // this thread, so reset the interrupt state on this thread and keep going.
        Thread.interrupted();
      }
    }
  };

  /**
   * Checks to see if a ScheduledFuture has exited successfully or thrown an error
   *
   * @param future scheduled future to check
   * @param taskQueue the running task queue from which the future came
   * @return true if the future should be removed
   */
  private static boolean checkTaskFailed(ScheduledFuture<?> future,
      ConcurrentLinkedQueue<ScheduledFuture<?>> taskQueue) {
    // Calling get() on a ScheduledFuture will block unless that scheduled task has
    // completed. We call isDone() here instead. If the scheduled task is done then
    // either it was a one-shot task, cancelled or an exception was thrown.
    if (future.isDone()) {
      // Now call get() to see if we get an exception.
      try {
        future.get();
        // If we get here, then a scheduled task exited but did not throw an error
        // or get canceled. This was likely a one-shot scheduled task (I don't think
        // we can tell if it's one-shot or not, I think we have to assume that it is
        // and that a recurring task would not normally be complete).
        return true;
      } catch (ExecutionException ee) {
        // An exception was thrown in the critical task. Throw the error here, which
        // will then be caught by the AccumuloUncaughtExceptionHandler which will
        // log the error and terminate the VM.
        if (taskQueue == CRITICAL_RUNNING_TASKS) {
          throw new ExecutionError("Critical scheduled background task failed.", ee);
        } else {
          LOG.error("Non-critical scheduled background task failed", ee);
          return true;
        }
      } catch (CancellationException ce) {
        // do nothing here as it appears that the task was canceled. Remove it from
        // the list of critical tasks
        return true;
      } catch (InterruptedException ie) {
        // current thread was interrupted waiting for get to return, which in theory,
        // shouldn't happen since the task is done.
        LOG.info("Interrupted while waiting to check on scheduled background task.");
        // Reset the interrupt state on this thread
        Thread.interrupted();
      }
    }
    return false;
  }

  static {
    SCHEDULED_FUTURE_CHECKER_POOL.execute(TASK_CHECKER);
  }

  public static void watchCriticalScheduledTask(ScheduledFuture<?> future) {
    CRITICAL_RUNNING_TASKS.add(future);
  }

  public static void watchCriticalFixedDelay(AccumuloConfiguration aconf, long intervalMillis,
      Runnable runnable) {
    ScheduledFuture<?> future = getServerThreadPools().createGeneralScheduledExecutorService(aconf)
        .scheduleWithFixedDelay(runnable, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    CRITICAL_RUNNING_TASKS.add(future);
  }

  public static void watchNonCriticalScheduledTask(ScheduledFuture<?> future) {
    NON_CRITICAL_RUNNING_TASKS.add(future);
  }

  public static void ensureRunning(ScheduledFuture<?> future, String message) {
    if (future.isDone()) {
      try {
        future.get();
      } catch (Exception e) {
        throw new IllegalStateException(message, e);
      }
      // it exited w/o exception, but we still expect it to be running so throw an exception.
      throw new IllegalStateException(message);
    }
  }

  /**
   * Resize ThreadPoolExecutor based on current value of maxThreads
   *
   * @param pool the ThreadPoolExecutor to modify
   * @param maxThreads supplier of maxThreads value
   * @param poolName name of the thread pool
   */
  public static void resizePool(final ThreadPoolExecutor pool, final IntSupplier maxThreads,
      String poolName) {
    int count = pool.getMaximumPoolSize();
    int newCount = maxThreads.getAsInt();
    if (count == newCount) {
      return;
    }
    LOG.info("Changing max threads for {} from {} to {}", poolName, count, newCount);
    if (newCount > count) {
      // increasing, increase the max first, or the core will fail to be increased
      pool.setMaximumPoolSize(newCount);
      pool.setCorePoolSize(newCount);
    } else {
      // decreasing, lower the core size first, or the max will fail to be lowered
      pool.setCorePoolSize(newCount);
      pool.setMaximumPoolSize(newCount);
    }

  }

  /**
   * Resize ThreadPoolExecutor based on current value of Property p
   *
   * @param pool the ThreadPoolExecutor to modify
   * @param conf the AccumuloConfiguration
   * @param p the property to base the size from
   */
  public static void resizePool(final ThreadPoolExecutor pool, final AccumuloConfiguration conf,
      final Property p) {
    resizePool(pool, () -> conf.getCount(p), p.getKey());
  }

  private final UncaughtExceptionHandler handler;
  private final AtomicBoolean metricsEnabled = new AtomicBoolean(true);
  private final AtomicReference<MeterRegistry> registry = new AtomicReference<>();
  private final List<ExecutorServiceMetrics> earlyExecutorServices = new ArrayList<>();

  private ThreadPools(UncaughtExceptionHandler ueh) {
    handler = ueh;
  }

  /**
   * Create a thread pool based on a thread pool related property. The pool will not be instrumented
   * without additional metrics. This method should be preferred, especially for short-lived pools.
   *
   * @param conf accumulo configuration
   * @param p thread pool related property
   * @return ExecutorService impl
   * @throws IllegalArgumentException if property is not handled
   */
  public ThreadPoolExecutor createExecutorService(final AccumuloConfiguration conf,
      final Property p) {
    return createExecutorService(conf, p, false);
  }

  /**
   * Create a thread pool based on a thread pool related property
   *
   * @param conf accumulo configuration
   * @param p thread pool related property
   * @param emitThreadPoolMetrics When set to true will emit metrics and register the metrics in a
   *        static registry. After the thread pool is deleted, there will still be metrics objects
   *        related to it in the static registry. There is no way to clean these leftover objects up
   *        therefore its recommended that this option only be set true for long-lived thread pools.
   *        Creating lots of short-lived thread pools and registering them can lead to out of memory
   *        errors over long time periods.
   * @return ExecutorService impl
   * @throws IllegalArgumentException if property is not handled
   */
  public ThreadPoolExecutor createExecutorService(final AccumuloConfiguration conf,
      final Property p, boolean emitThreadPoolMetrics) {
    ThreadPoolExecutorBuilder builder;
    switch (p) {
      case GENERAL_THREADPOOL_SIZE:
        return createScheduledExecutorService(conf.getCount(p), GENERAL_SERVER_POOL.poolName,
            emitThreadPoolMetrics);
      case MANAGER_STATUS_THREAD_POOL_SIZE:
        builder = getPoolBuilder(MANAGER_STATUS_POOL);
        int threads = conf.getCount(p);
        if (threads == 0) {
          builder.numCoreThreads(0).numMaxThreads(Integer.MAX_VALUE).withTimeOut(60L, SECONDS)
              .withQueue(new SynchronousQueue<>());
        } else {
          builder.numCoreThreads(threads);
        }
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_MINC_MAXCONCURRENT:
        builder = getPoolBuilder(TSERVER_MINOR_COMPACTOR_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(0L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_MIGRATE_MAXCONCURRENT:
        builder = getPoolBuilder(TSERVER_MIGRATIONS_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(0L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_ASSIGNMENT_MAXCONCURRENT:
        builder = getPoolBuilder(TSERVER_ASSIGNMENT_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(0L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_SUMMARY_RETRIEVAL_THREADS:
        builder = getPoolBuilder(TSERVER_SUMMARY_RETRIEVAL_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_SUMMARY_REMOTE_THREADS:
        builder = getPoolBuilder(TSERVER_SUMMARY_REMOTE_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_SUMMARY_PARTITION_THREADS:
        builder = getPoolBuilder(TSERVER_SUMMARY_PARTITION_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_CONDITIONAL_UPDATE_THREADS_ROOT:
        builder = getPoolBuilder(TSERVER_CONDITIONAL_UPDATE_ROOT_POOL)
            .numCoreThreads(conf.getCount(p)).withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_CONDITIONAL_UPDATE_THREADS_META:
        builder = getPoolBuilder(TSERVER_CONDITIONAL_UPDATE_META_POOL)
            .numCoreThreads(conf.getCount(p)).withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case TSERV_CONDITIONAL_UPDATE_THREADS_USER:
        builder = getPoolBuilder(TSERVER_CONDITIONAL_UPDATE_USER_POOL)
            .numCoreThreads(conf.getCount(p)).withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case GC_DELETE_WAL_THREADS:
        return getPoolBuilder(GC_WAL_DELETE_POOL).numCoreThreads(conf.getCount(p)).build();
      case GC_DELETE_THREADS:
        return getPoolBuilder(GC_DELETE_POOL).numCoreThreads(conf.getCount(p)).build();
      case COMPACTION_COORDINATOR_RESERVATION_THREADS_ROOT:
        builder = getPoolBuilder(COORDINATOR_RESERVATION_ROOT_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case COMPACTION_COORDINATOR_RESERVATION_THREADS_META:
        builder = getPoolBuilder(COORDINATOR_RESERVATION_META_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      case COMPACTION_COORDINATOR_RESERVATION_THREADS_USER:
        builder = getPoolBuilder(COORDINATOR_RESERVATION_USER_POOL).numCoreThreads(conf.getCount(p))
            .withTimeOut(60L, MILLISECONDS);
        if (emitThreadPoolMetrics) {
          builder.enableThreadPoolMetrics();
        }
        return builder.build();
      default:
        throw new IllegalArgumentException("Unhandled thread pool property: " + p);
    }
  }

  /**
   * Fet a fluent-style pool builder.
   *
   * @param pool the constant pool name
   */
  public ThreadPoolExecutorBuilder getPoolBuilder(@NonNull final ThreadPoolNames pool) {
    return new ThreadPoolExecutorBuilder(pool.poolName);
  }

  /**
   * Get a fluent-style pool builder.
   *
   * @param name the pool name - the name trimed and prepended with the ACCUMULO_POOL_PREFIX so that
   *        pool names begin with a consistent prefix.
   */
  public ThreadPoolExecutorBuilder getPoolBuilder(@NonNull final String name) {
    String trimmed = name.trim();
    if (trimmed.startsWith(ACCUMULO_POOL_PREFIX.poolName)) {
      return new ThreadPoolExecutorBuilder(trimmed);
    } else {
      if (trimmed.startsWith(".")) {
        return new ThreadPoolExecutorBuilder(ACCUMULO_POOL_PREFIX.poolName + trimmed);
      } else {
        return new ThreadPoolExecutorBuilder(ACCUMULO_POOL_PREFIX.poolName + "." + trimmed);
      }
    }
  }

  public class ThreadPoolExecutorBuilder {
    final String name;
    int coreThreads = 0;
    int maxThreads = -1;
    long timeOut = DEFAULT_TIMEOUT_MILLISECS;
    TimeUnit units = MILLISECONDS;
    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    OptionalInt priority = OptionalInt.empty();
    boolean emitThreadPoolMetrics = false;

    /**
     * A fluent-style build to create a ThreadPoolExecutor. The name is used when creating
     * named-threads for the pool.
     */
    ThreadPoolExecutorBuilder(@NonNull final String name) {
      this.name = name;
    }

    public ThreadPoolExecutor build() {
      Preconditions.checkArgument(coreThreads >= 0,
          "The number of core threads must be 0 or larger");
      if (maxThreads < 0) {
        // create a fixed pool with maxThread = coreThreads if core threads set.
        maxThreads = coreThreads == 0 ? 1 : coreThreads;
      }
      Preconditions.checkArgument(maxThreads >= coreThreads,
          "The number of max threads must be greater than 0 and greater than or equal to the number of core threads");
      Preconditions.checkArgument(
          priority.orElse(1) >= Thread.MIN_PRIORITY && priority.orElse(1) <= Thread.MAX_PRIORITY,
          "invalid thread priority, range must be Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY");

      return createThreadPool(coreThreads, maxThreads, timeOut, units, name, queue, priority,
          emitThreadPoolMetrics);
    }

    /**
     * Set the number of coreThreads. See {@link java.util.concurrent.ThreadPoolExecutor}
     *
     * @param coreThreads the number of core thread, must be 0 or larger.
     * @return fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder numCoreThreads(int coreThreads) {
      this.coreThreads = coreThreads;
      return this;
    }

    /**
     * Set the maximum number of threads in the pool. See
     * {@link java.util.concurrent.ThreadPoolExecutor}. If the maxThreads is not set, defaults to
     * the number of core threads (if set) resulting in a fixed pool. If the number of core threads
     * is not set, defaults to a single thread.
     *
     * @param maxThreads max number of threads. Must be greater than 0 and equal or greater that the
     *        number of core threads.
     *
     * @return fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder numMaxThreads(int maxThreads) {
      this.maxThreads = maxThreads;
      return this;
    }

    /**
     * Set the thread keep-alive time. See {@link java.util.concurrent.ThreadPoolExecutor}
     *
     * @param timeOut the thread keep alive time.
     * @param units the keep alive time units.
     * @return fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder withTimeOut(long timeOut, @NonNull TimeUnit units) {
      this.timeOut = timeOut;
      this.units = units;
      return this;
    }

    /**
     * Set the queue that will hold runnable tasks before execution. See
     * {@link java.util.concurrent.ThreadPoolExecutor}
     *
     * @param queue the work queue used to hold tasks before they are executed.
     * @return fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder withQueue(@NonNull final BlockingQueue<Runnable> queue) {
      this.queue = queue;
      return this;
    }

    public ThreadPoolExecutorBuilder atPriority(@NonNull final OptionalInt priority) {
      this.priority = priority;
      return this;
    }

    /**
     * When set to true will emit metrics and register the metrics in a registry. After the thread
     * pool is deleted, there will still be metrics objects related to it in the static registry.
     * There is no way to clean these leftover objects up therefore its recommended that this option
     * only be set true for long-lived thread pools. Creating lots of short-lived thread pools and
     * registering them can lead to out of memory errors over long time periods.
     *
     * @return a fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder enableThreadPoolMetrics() {
      return enableThreadPoolMetrics(true);
    }

    /**
     * Optionally set to register pool metrics. When set to true will emit metrics and register the
     * metrics in a registry. After the thread pool is deleted, there will still be metrics objects
     * related to it in the static registry. There is no way to clean these leftover objects up
     * therefore its recommended that this option only be set true for long-lived thread pools.
     * Creating lots of short-lived thread pools and registering them can lead to out of memory
     * errors over long time periods.
     *
     * @return a fluent-style builder instance
     */
    public ThreadPoolExecutorBuilder enableThreadPoolMetrics(final boolean enable) {
      this.emitThreadPoolMetrics = enable;
      return this;
    }
  }

  /**
   * Create a named thread pool
   *
   * @param coreThreads number of threads
   * @param maxThreads max number of threads
   * @param timeOut core thread time out
   * @param units core thread time out units
   * @param name thread pool name
   * @param queue queue to use for tasks
   * @param priority thread priority
   * @param emitThreadPoolMetrics When set to true will emit metrics and register the metrics in a
   *        static registry. After the thread pool is deleted, there will still be metrics objects
   *        related to it in the static registry. There is no way to clean these leftover objects up
   *        therefore its recommended that this option only be set true for long-lived thread pools.
   *        Creating lots of short-lived thread pools and registering them can lead to out of memory
   *        errors over long time periods.
   * @return ThreadPoolExecutor
   */
  private ThreadPoolExecutor createThreadPool(final int coreThreads, final int maxThreads,
      final long timeOut, final TimeUnit units, final String name,
      final BlockingQueue<Runnable> queue, final OptionalInt priority,
      final boolean emitThreadPoolMetrics) {
    LOG.trace(
        "Creating ThreadPoolExecutor for {} with {} core threads and {} max threads {} {} timeout",
        name, coreThreads, maxThreads, timeOut, units);
    var result = new ThreadPoolExecutor(coreThreads, maxThreads, timeOut, units, queue,
        new NamedThreadFactory(name, priority, handler)) {

      @Override
      public void execute(@NonNull Runnable arg0) {
        super.execute(TraceUtil.wrap(arg0));
      }

      @Override
      public boolean remove(Runnable task) {
        return super.remove(TraceUtil.wrap(task));
      }

      @Override
      @NonNull
      public <T> Future<T> submit(@NonNull Callable<T> task) {
        return super.submit(TraceUtil.wrap(task));
      }

      @Override
      @NonNull
      public <T> Future<T> submit(@NonNull Runnable task, T result) {
        return super.submit(TraceUtil.wrap(task), result);
      }

      @Override
      @NonNull
      public Future<?> submit(@NonNull Runnable task) {
        return super.submit(TraceUtil.wrap(task));
      }
    };
    if (timeOut > 0) {
      result.allowCoreThreadTimeOut(true);
    }
    if (emitThreadPoolMetrics) {
      addExecutorServiceMetrics(result, name);
    }
    return result;
  }

  /*
   * If you need the server-side shared ScheduledThreadPoolExecutor, then use
   * ServerContext.getScheduledExecutor()
   */
  public ScheduledThreadPoolExecutor
      createGeneralScheduledExecutorService(AccumuloConfiguration conf) {
    return (ScheduledThreadPoolExecutor) createExecutorService(conf,
        Property.GENERAL_THREADPOOL_SIZE, true);
  }

  /**
   * Create a named ScheduledThreadPool. The pool will not be instrumented without additional
   * metrics. This method should be preferred, especially for short-lived pools.
   *
   * @param numThreads number of threads
   * @param name thread pool name
   * @return ScheduledThreadPoolExecutor
   */
  public ScheduledThreadPoolExecutor createScheduledExecutorService(int numThreads,
      final String name) {
    return createScheduledExecutorService(numThreads, name, false);
  }

  /**
   * Create a named ScheduledThreadPool
   *
   * @param numThreads number of threads
   * @param name thread pool name
   * @param emitThreadPoolMetrics When set to true will emit metrics and register the metrics in a
   *        static registry. After the thread pool is deleted, there will still be metrics objects
   *        related to it in the static registry. There is no way to clean these leftover objects up
   *        therefore its recommended that this option only be set true for long-lived thread pools.
   *        Creating lots of short-lived thread pools and registering them can lead to out of memory
   *        errors over long time periods.
   * @return ScheduledThreadPoolExecutor
   */
  public ScheduledThreadPoolExecutor createScheduledExecutorService(int numThreads,
      final String name, boolean emitThreadPoolMetrics) {
    LOG.trace("Creating ScheduledThreadPoolExecutor for {} with {} threads", name, numThreads);
    var result =
        new ScheduledThreadPoolExecutor(numThreads, new NamedThreadFactory(name, handler)) {

          @Override
          public void execute(@NonNull Runnable command) {
            super.execute(TraceUtil.wrap(command));
          }

          @Override
          @NonNull
          public <V> ScheduledFuture<V> schedule(@NonNull Callable<V> callable, long delay,
              @NonNull TimeUnit unit) {
            return super.schedule(TraceUtil.wrap(callable), delay, unit);
          }

          @Override
          @NonNull
          public ScheduledFuture<?> schedule(@NonNull Runnable command, long delay,
              @NonNull TimeUnit unit) {
            return super.schedule(TraceUtil.wrap(command), delay, unit);
          }

          @Override
          @NonNull
          public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable command,
              long initialDelay, long period, @NonNull TimeUnit unit) {
            return super.scheduleAtFixedRate(TraceUtil.wrap(command), initialDelay, period, unit);
          }

          @Override
          @NonNull
          public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable command,
              long initialDelay, long delay, @NonNull TimeUnit unit) {
            return super.scheduleWithFixedDelay(TraceUtil.wrap(command), initialDelay, delay, unit);
          }

          @Override
          @NonNull
          public <T> Future<T> submit(@NonNull Callable<T> task) {
            return super.submit(TraceUtil.wrap(task));
          }

          @Override
          @NonNull
          public <T> Future<T> submit(@NonNull Runnable task, T result) {
            return super.submit(TraceUtil.wrap(task), result);
          }

          @Override
          @NonNull
          public Future<?> submit(@NonNull Runnable task) {
            return super.submit(TraceUtil.wrap(task));
          }

          @Override
          public boolean remove(Runnable task) {
            return super.remove(TraceUtil.wrap(task));
          }

        };
    if (emitThreadPoolMetrics) {
      addExecutorServiceMetrics(result, name);
    }
    return result;
  }

  private void addExecutorServiceMetrics(ExecutorService executor, String name) {
    if (!metricsEnabled.get()) {
      return;
    }
    ExecutorServiceMetrics esm = new ExecutorServiceMetrics(executor, name, List.of());
    synchronized (earlyExecutorServices) {
      MeterRegistry r = registry.get();
      if (r != null) {
        esm.bindTo(r);
      } else {
        earlyExecutorServices.add(esm);
      }
    }
  }

  public void setMeterRegistry(MeterRegistry r) {
    if (registry.compareAndSet(null, r)) {
      synchronized (earlyExecutorServices) {
        earlyExecutorServices.forEach(e -> e.bindTo(r));
        earlyExecutorServices.clear();
      }
    } else {
      throw new IllegalStateException("setMeterRegistry called more than once");
    }
  }

  /**
   * Called by MetricsInfoImpl.init on the server side if metrics are disabled. ClientContext calls
   * {@code #getClientThreadPools(AccumuloConfiguration, UncaughtExceptionHandler)} above.
   */
  public void disableThreadPoolMetrics() {
    metricsEnabled.set(false);
    synchronized (earlyExecutorServices) {
      earlyExecutorServices.clear();
    }
  }

}
