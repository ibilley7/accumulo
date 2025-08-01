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
package org.apache.accumulo.compactor;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_ENTRIES_READ;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_ENTRIES_WRITTEN;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_CANCELLED;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_COMPLETED;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_FAILED;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_FAILURES_CONSECUTIVE;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_FAILURES_TERMINATION;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_IN_PROGRESS;
import static org.apache.accumulo.core.metrics.Metric.COMPACTOR_MAJC_STUCK;
import static org.apache.accumulo.core.util.LazySingletons.RANDOM;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.apache.accumulo.core.cli.ConfigOpts;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.servers.ServerId;
import org.apache.accumulo.core.client.admin.servers.ServerId.Type;
import org.apache.accumulo.core.clientImpl.thrift.SecurityErrorCode;
import org.apache.accumulo.core.clientImpl.thrift.TInfo;
import org.apache.accumulo.core.clientImpl.thrift.TableOperation;
import org.apache.accumulo.core.clientImpl.thrift.TableOperationExceptionType;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.clientImpl.thrift.ThriftTableOperationException;
import org.apache.accumulo.core.compaction.thrift.CompactionCoordinatorService;
import org.apache.accumulo.core.compaction.thrift.CompactionCoordinatorService.Client;
import org.apache.accumulo.core.compaction.thrift.CompactorService;
import org.apache.accumulo.core.compaction.thrift.TCompactionState;
import org.apache.accumulo.core.compaction.thrift.TCompactionStatusUpdate;
import org.apache.accumulo.core.compaction.thrift.TNextCompactionJob;
import org.apache.accumulo.core.compaction.thrift.UnknownCompactionIdException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVIterator;
import org.apache.accumulo.core.iteratorsImpl.system.SystemIteratorUtil;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.lock.ServiceLock.LockWatcher;
import org.apache.accumulo.core.lock.ServiceLockData;
import org.apache.accumulo.core.lock.ServiceLockData.ServiceDescriptor;
import org.apache.accumulo.core.lock.ServiceLockData.ServiceDescriptors;
import org.apache.accumulo.core.lock.ServiceLockData.ThriftService;
import org.apache.accumulo.core.lock.ServiceLockPaths.ServiceLockPath;
import org.apache.accumulo.core.lock.ServiceLockSupport;
import org.apache.accumulo.core.lock.ServiceLockSupport.ServiceLockWatcher;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.metadata.ReferencedTabletFile;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.schema.DataFileValue;
import org.apache.accumulo.core.metadata.schema.ExternalCompactionId;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType;
import org.apache.accumulo.core.metrics.MetricsInfo;
import org.apache.accumulo.core.metrics.MetricsProducer;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.core.spi.crypto.CryptoService;
import org.apache.accumulo.core.tabletserver.thrift.ActiveCompaction;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionKind;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.Timer;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.util.compaction.ExternalCompactionUtil;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.server.AbstractServer;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.client.ClientServiceHandler;
import org.apache.accumulo.server.compaction.CompactionConfigStorage;
import org.apache.accumulo.server.compaction.CompactionInfo;
import org.apache.accumulo.server.compaction.CompactionWatcher;
import org.apache.accumulo.server.compaction.FileCompactor;
import org.apache.accumulo.server.compaction.PausedCompactionMetrics;
import org.apache.accumulo.server.compaction.RetryableThriftCall;
import org.apache.accumulo.server.compaction.RetryableThriftCall.RetriesExceededException;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.accumulo.server.rpc.ThriftProcessorTypes;
import org.apache.accumulo.tserver.log.LogSorter;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

public class Compactor extends AbstractServer implements MetricsProducer, CompactorService.Iface {

  public interface FileCompactorRunnable extends Runnable {
    /**
     * Unable to create a constructor in an anonymous class so this method serves to initialize the
     * object so that {@code #getFileCompactor()} returns a non-null reference.
     */
    void initialize() throws RetriesExceededException;

    AtomicReference<FileCompactor> getFileCompactor();

    Duration getCompactionAge();
  }

  private static class ConsecutiveErrorHistory extends HashMap<TableId,HashMap<String,AtomicLong>> {

    private static final long serialVersionUID = 1L;

    public long getTotalFailures() {
      long total = 0;
      for (TableId tid : keySet()) {
        total += getTotalTableFailures(tid);
      }
      return total;
    }

    public long getTotalTableFailures(TableId tid) {
      long total = 0;
      for (AtomicLong failures : get(tid).values()) {
        total += failures.get();
      }
      return total;
    }

    /**
     * Add error for table
     *
     * @param tid table id
     * @param error exception
     */
    public void addError(TableId tid, Throwable error) {
      computeIfAbsent(tid, t -> new HashMap<String,AtomicLong>())
          .computeIfAbsent(error.toString(), e -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder();
      for (TableId tid : keySet()) {
        buf.append("\nTable: ").append(tid);
        for (Entry<String,AtomicLong> error : get(tid).entrySet()) {
          buf.append("\n\tException: ").append(error.getKey()).append(", count: ")
              .append(error.getValue().get());
        }
      }
      return buf.toString();
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(Compactor.class);

  private static final long TEN_MEGABYTES = 10485760;

  protected static final CompactionJobHolder JOB_HOLDER = new CompactionJobHolder();

  private final UUID compactorId = UUID.randomUUID();
  protected final AtomicReference<ExternalCompactionId> currentCompactionId =
      new AtomicReference<>();

  private ServiceLock compactorLock;
  private final PausedCompactionMetrics pausedMetrics = new PausedCompactionMetrics();

  private final AtomicBoolean compactionRunning = new AtomicBoolean(false);
  private final ConsecutiveErrorHistory errorHistory = new ConsecutiveErrorHistory();
  private final AtomicLong completed = new AtomicLong(0);
  private final AtomicLong cancelled = new AtomicLong(0);
  private final AtomicLong failed = new AtomicLong(0);
  private final AtomicLong terminated = new AtomicLong(0);

  @VisibleForTesting
  protected Compactor(ConfigOpts opts, String[] args) {
    super(ServerId.Type.COMPACTOR, opts, ServerContext::new, args);
  }

  @Override
  protected String getResourceGroupPropertyValue(SiteConfiguration conf) {
    return conf.get(Property.COMPACTOR_GROUP_NAME);
  }

  private long getTotalEntriesRead() {
    return FileCompactor.getTotalEntriesRead();
  }

  private long getTotalEntriesWritten() {
    return FileCompactor.getTotalEntriesWritten();
  }

  private long compactionInProgress() {
    return compactionRunning.get() ? 1 : 0;
  }

  private double getConsecutiveFailures() {
    return errorHistory.getTotalFailures();
  }

  private double getCancellations() {
    return cancelled.get();
  }

  private double getCompletions() {
    return completed.get();
  }

  private double getFailures() {
    return failed.get();
  }

  private double getTerminated() {
    return terminated.get();
  }

  @Override
  public void registerMetrics(MeterRegistry registry) {
    super.registerMetrics(registry);
    final String rgName = getResourceGroup().canonical();
    FunctionCounter.builder(COMPACTOR_ENTRIES_READ.getName(), this, Compactor::getTotalEntriesRead)
        .description(COMPACTOR_ENTRIES_READ.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    FunctionCounter
        .builder(COMPACTOR_ENTRIES_WRITTEN.getName(), this, Compactor::getTotalEntriesWritten)
        .description(COMPACTOR_ENTRIES_WRITTEN.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    Gauge.builder(COMPACTOR_MAJC_IN_PROGRESS.getName(), this, Compactor::compactionInProgress)
        .description(COMPACTOR_MAJC_IN_PROGRESS.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    LongTaskTimer timer = LongTaskTimer.builder(COMPACTOR_MAJC_STUCK.getName())
        .description(COMPACTOR_MAJC_STUCK.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    FunctionCounter.builder(COMPACTOR_MAJC_CANCELLED.getName(), this, Compactor::getCancellations)
        .description(COMPACTOR_MAJC_CANCELLED.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    FunctionCounter.builder(COMPACTOR_MAJC_COMPLETED.getName(), this, Compactor::getCompletions)
        .description(COMPACTOR_MAJC_COMPLETED.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    FunctionCounter.builder(COMPACTOR_MAJC_FAILED.getName(), this, Compactor::getFailures)
        .description(COMPACTOR_MAJC_FAILED.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    FunctionCounter
        .builder(COMPACTOR_MAJC_FAILURES_TERMINATION.getName(), this, Compactor::getTerminated)
        .description(COMPACTOR_MAJC_FAILURES_TERMINATION.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    Gauge
        .builder(COMPACTOR_MAJC_FAILURES_CONSECUTIVE.getName(), this,
            Compactor::getConsecutiveFailures)
        .description(COMPACTOR_MAJC_FAILURES_CONSECUTIVE.getDescription())
        .tags(List.of(Tag.of("queue.id", rgName))).register(registry);
    CompactionWatcher.setTimer(timer);
  }

  protected void startCancelChecker(ScheduledThreadPoolExecutor schedExecutor,
      long timeBetweenChecks) {
    ThreadPools.watchCriticalScheduledTask(schedExecutor.scheduleWithFixedDelay(
        this::checkIfCanceled, 0, timeBetweenChecks, TimeUnit.MILLISECONDS));
  }

  protected void checkIfCanceled() {
    TExternalCompactionJob job = JOB_HOLDER.getJob();
    if (job != null) {
      try {
        var extent = KeyExtent.fromThrift(job.getExtent());
        var ecid = ExternalCompactionId.of(job.getExternalCompactionId());

        TabletMetadata tabletMeta =
            getContext().getAmple().readTablet(extent, ColumnType.ECOMP, ColumnType.PREV_ROW);
        if (tabletMeta == null || !tabletMeta.getExternalCompactions().containsKey(ecid)) {
          // table was deleted OR tablet was split or merged OR tablet no longer thinks compaction
          // is running for some reason
          LOG.info("Cancelling compaction {} that no longer has a metadata entry at {}", ecid,
              extent);
          JOB_HOLDER.cancel(job.getExternalCompactionId());
          return;
        }

        var tableState = getContext().getTableState(extent.tableId());
        if (tableState != TableState.ONLINE) {
          LOG.info("Cancelling compaction {} because table state is {}", ecid, tableState);
          JOB_HOLDER.cancel(job.getExternalCompactionId());
          return;
        }

        if (job.getKind() == TCompactionKind.USER) {

          var cconf =
              CompactionConfigStorage.getConfig(getContext(), FateId.fromThrift(job.getFateId()));

          if (cconf == null) {
            LOG.info("Cancelling compaction {} for user compaction that no longer exists {} {}",
                ecid, FateId.fromThrift(job.getFateId()), extent);
            JOB_HOLDER.cancel(job.getExternalCompactionId());
          }
        }
      } catch (RuntimeException | KeeperException e) {
        LOG.warn("Failed to check if compaction {} for {} was canceled.",
            job.getExternalCompactionId(), KeyExtent.fromThrift(job.getExtent()), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Set up nodes and locks in ZooKeeper for this Compactor
   *
   * @param clientAddress address of this Compactor
   * @throws KeeperException zookeeper error
   * @throws InterruptedException thread interrupted
   */
  protected void announceExistence(HostAndPort clientAddress)
      throws KeeperException, InterruptedException {

    final ZooReaderWriter zoo = getContext().getZooSession().asReaderWriter();
    final ServiceLockPath path =
        getContext().getServerPaths().createCompactorPath(getResourceGroup(), clientAddress);
    ServiceLockSupport.createNonHaServiceLockPath(Type.COMPACTOR, zoo, path);
    compactorLock = new ServiceLock(getContext().getZooSession(), path, compactorId);
    LockWatcher lw = new ServiceLockWatcher(Type.COMPACTOR, () -> getShutdownComplete().get(),
        (type) -> getContext().getLowMemoryDetector().logGCInfo(getConfiguration()));

    try {
      for (int i = 0; i < 25; i++) {
        zoo.putPersistentData(path.toString(), new byte[0], NodeExistsPolicy.SKIP);

        ServiceDescriptors descriptors = new ServiceDescriptors();
        for (ThriftService svc : new ThriftService[] {ThriftService.CLIENT,
            ThriftService.COMPACTOR}) {
          descriptors.addService(new ServiceDescriptor(compactorId, svc,
              ExternalCompactionUtil.getHostPortString(clientAddress), this.getResourceGroup()));
        }

        if (compactorLock.tryLock(lw, new ServiceLockData(descriptors))) {
          LOG.debug("Obtained Compactor lock {}", compactorLock.getLockPath());
          return;
        }
        LOG.info("Waiting for Compactor lock");
        sleepUninterruptibly(5, TimeUnit.SECONDS);
      }
      String msg = "Too many retries, exiting.";
      LOG.info(msg);
      throw new RuntimeException(msg);
    } catch (Exception e) {
      LOG.info("Could not obtain tablet server lock, exiting.", e);
      throw new RuntimeException(e);
    }
  }

  protected CompactorService.Iface getCompactorThriftHandlerInterface() {
    return this;
  }

  /**
   * Start this Compactors thrift service to handle incoming client requests
   *
   * @throws UnknownHostException host unknown
   */
  protected void startCompactorClientService() throws UnknownHostException {
    ClientServiceHandler clientHandler = new ClientServiceHandler(getContext());
    var processor = ThriftProcessorTypes.getCompactorTProcessor(this, clientHandler,
        getCompactorThriftHandlerInterface(), getContext());
    updateThriftServer(() -> {
      return TServerUtils.createThriftServer(getContext(), getBindAddress(),
          Property.COMPACTOR_CLIENTPORT, processor, this.getClass().getSimpleName(),
          Property.COMPACTOR_PORTSEARCH, Property.COMPACTOR_MINTHREADS,
          Property.COMPACTOR_MINTHREADS_TIMEOUT, Property.COMPACTOR_THREADCHECK);
    }, true);
  }

  /**
   * Cancel the compaction with this id.
   *
   * @param externalCompactionId compaction id
   * @throws UnknownCompactionIdException if the externalCompactionId does not match the currently
   *         executing compaction
   * @throws TException thrift error
   */
  private void cancel(String externalCompactionId) throws TException {
    if (JOB_HOLDER.cancel(externalCompactionId)) {
      LOG.info("Cancel requested for compaction job {}", externalCompactionId);
    } else {
      throw new UnknownCompactionIdException();
    }
  }

  @Override
  public void cancel(TInfo tinfo, TCredentials credentials, String externalCompactionId)
      throws TException {
    TableId tableId = JOB_HOLDER.getTableId();
    try {
      NamespaceId nsId = getContext().getNamespaceId(tableId);
      if (!getContext().getSecurityOperation().canCompact(credentials, tableId, nsId)) {
        throw new AccumuloSecurityException(credentials.getPrincipal(),
            SecurityErrorCode.PERMISSION_DENIED).asThriftException();
      }
    } catch (TableNotFoundException e) {
      throw new ThriftTableOperationException(tableId.canonical(), null,
          TableOperation.COMPACT_CANCEL, TableOperationExceptionType.NOTFOUND, e.getMessage());
    }

    cancel(externalCompactionId);
  }

  /**
   * Send an update to the CompactionCoordinator for this job
   *
   * @param job compactionJob
   * @param update status update
   * @throws RetriesExceededException thrown when retries have been exceeded
   */
  protected void updateCompactionState(TExternalCompactionJob job, TCompactionStatusUpdate update)
      throws RetriesExceededException {
    RetryableThriftCall<String> thriftCall =
        new RetryableThriftCall<>(1000, RetryableThriftCall.MAX_WAIT_TIME, 25, () -> {
          Client coordinatorClient = getCoordinatorClient();
          try {
            LOG.trace("Attempting to update compaction state in coordinator {}",
                job.getExternalCompactionId());
            coordinatorClient.updateCompactionStatus(TraceUtil.traceInfo(), getContext().rpcCreds(),
                job.getExternalCompactionId(), update, System.currentTimeMillis());
            return "";
          } finally {
            ThriftUtil.returnClient(coordinatorClient, getContext());
          }
        });
    thriftCall.run();
  }

  /**
   * Notify the CompactionCoordinator the job failed
   *
   * @param job current compaction job
   * @throws RetriesExceededException thrown when retries have been exceeded
   */
  protected void updateCompactionFailed(TExternalCompactionJob job, String cause)
      throws RetriesExceededException {
    RetryableThriftCall<String> thriftCall =
        new RetryableThriftCall<>(1000, RetryableThriftCall.MAX_WAIT_TIME, 25, () -> {
          Client coordinatorClient = getCoordinatorClient();
          try {
            coordinatorClient.compactionFailed(TraceUtil.traceInfo(), getContext().rpcCreds(),
                job.getExternalCompactionId(), job.extent, cause);
            return "";
          } finally {
            ThriftUtil.returnClient(coordinatorClient, getContext());
          }
        });
    thriftCall.run();
  }

  /**
   * Update the CompactionCoordinator with the stats from the completed job
   *
   * @param job current compaction job
   * @param stats compaction stats
   * @throws RetriesExceededException thrown when retries have been exceeded
   */
  protected void updateCompactionCompleted(TExternalCompactionJob job, TCompactionStats stats)
      throws RetriesExceededException {
    RetryableThriftCall<String> thriftCall =
        new RetryableThriftCall<>(1000, RetryableThriftCall.MAX_WAIT_TIME, 25, () -> {
          Client coordinatorClient = getCoordinatorClient();
          try {
            coordinatorClient.compactionCompleted(TraceUtil.traceInfo(), getContext().rpcCreds(),
                job.getExternalCompactionId(), job.extent, stats);
            return "";
          } finally {
            ThriftUtil.returnClient(coordinatorClient, getContext());
          }
        });
    thriftCall.run();
  }

  /**
   * Get the next job to run
   *
   * @param uuid uuid supplier
   * @return CompactionJob
   * @throws RetriesExceededException thrown when retries have been exceeded
   */
  protected TNextCompactionJob getNextJob(Supplier<UUID> uuid) throws RetriesExceededException {
    final long startingWaitTime =
        getConfiguration().getTimeInMillis(Property.COMPACTOR_MIN_JOB_WAIT_TIME);
    final long maxWaitTime =
        getConfiguration().getTimeInMillis(Property.COMPACTOR_MAX_JOB_WAIT_TIME);

    RetryableThriftCall<TNextCompactionJob> nextJobThriftCall =
        new RetryableThriftCall<>(startingWaitTime, maxWaitTime, 0, () -> {
          Client coordinatorClient = getCoordinatorClient();
          try {
            ExternalCompactionId eci = ExternalCompactionId.generate(uuid.get());
            LOG.trace("Attempting to get next job, eci = {}", eci);
            currentCompactionId.set(eci);
            return coordinatorClient.getCompactionJob(TraceUtil.traceInfo(),
                getContext().rpcCreds(), this.getResourceGroup().canonical(),
                ExternalCompactionUtil.getHostPortString(getAdvertiseAddress()), eci.toString());
          } catch (Exception e) {
            currentCompactionId.set(null);
            throw e;
          } finally {
            ThriftUtil.returnClient(coordinatorClient, getContext());
          }
        });
    return nextJobThriftCall.run();
  }

  /**
   * Get the client to the CompactionCoordinator
   *
   * @return compaction coordinator client
   * @throws TTransportException when unable to get client
   */
  protected CompactionCoordinatorService.Client getCoordinatorClient() throws TTransportException {
    var coordinatorHost = ExternalCompactionUtil.findCompactionCoordinator(getContext());
    if (coordinatorHost.isEmpty()) {
      throw new TTransportException("Unable to get CompactionCoordinator address from ZooKeeper");
    }
    LOG.trace("CompactionCoordinator address is: {}", coordinatorHost.orElseThrow());
    return ThriftUtil.getClient(ThriftClientTypes.COORDINATOR, coordinatorHost.orElseThrow(),
        getContext());
  }

  /**
   * Create compaction runnable
   *
   * @param job compaction job
   * @param totalInputEntries object to capture total entries
   * @param totalInputBytes object to capture input file size
   * @param started started latch
   * @param stopped stopped latch
   * @param err reference to error
   * @return Runnable compaction job
   */
  protected FileCompactorRunnable createCompactionJob(final TExternalCompactionJob job,
      final LongAdder totalInputEntries, final LongAdder totalInputBytes,
      final CountDownLatch started, final CountDownLatch stopped,
      final AtomicReference<Throwable> err) {

    return new FileCompactorRunnable() {

      private final AtomicReference<FileCompactor> compactor = new AtomicReference<>();
      private volatile Timer compactionStartTime;

      @Override
      public void initialize() throws RetriesExceededException {
        LOG.info("Starting up compaction runnable for job: {}", job);
        this.compactionStartTime = Timer.startNew();
        TCompactionStatusUpdate update = new TCompactionStatusUpdate(TCompactionState.STARTED,
            "Compaction started", -1, -1, -1, getCompactionAge().toNanos());
        updateCompactionState(job, update);
        final var extent = KeyExtent.fromThrift(job.getExtent());
        final AccumuloConfiguration aConfig;
        final TableConfiguration tConfig = getContext().getTableConfiguration(extent.tableId());

        if (!job.getOverrides().isEmpty()) {
          aConfig = new ConfigurationCopy(tConfig);
          job.getOverrides().forEach(((ConfigurationCopy) aConfig)::set);
          LOG.debug("Overriding table properties with {}", job.getOverrides());
        } else {
          aConfig = tConfig;
        }

        final ReferencedTabletFile outputFile =
            new ReferencedTabletFile(new Path(job.getOutputFile()));

        final Map<StoredTabletFile,DataFileValue> files = new TreeMap<>();
        job.getFiles().forEach(f -> {
          long estEntries = f.getEntries();
          StoredTabletFile stf = new StoredTabletFile(f.getMetadataFileEntry());
          // This happens with bulk import files
          if (estEntries == 0) {
            estEntries =
                estimateOverlappingEntries(extent, stf, aConfig, tConfig.getCryptoService());
          }
          files.put(stf, new DataFileValue(f.getSize(), estEntries, f.getTimestamp()));
          totalInputEntries.add(estEntries);
          totalInputBytes.add(f.getSize());
        });

        final List<IteratorSetting> iters = new ArrayList<>();
        job.getIteratorSettings().getIterators()
            .forEach(tis -> iters.add(SystemIteratorUtil.toIteratorSetting(tis)));

        final ExtCEnv cenv = new ExtCEnv(JOB_HOLDER, getResourceGroup());
        compactor.set(
            new FileCompactor(getContext(), extent, files, outputFile, job.isPropagateDeletes(),
                cenv, iters, aConfig, tConfig.getCryptoService(), pausedMetrics));

      }

      @Override
      public AtomicReference<FileCompactor> getFileCompactor() {
        return compactor;
      }

      @Override
      public void run() {
        Preconditions.checkState(compactor.get() != null, "initialize not called");
        // Its only expected that a single compaction runs at a time. Multiple compactions running
        // at a time could cause odd behavior like out of order and unexpected thrift calls to the
        // coordinator. This is a sanity check to ensure the expectation is met. Should this check
        // ever fail, it means there is a bug elsewhere.
        Preconditions.checkState(compactionRunning.compareAndSet(false, true));
        try {

          LOG.trace("Starting compactor");
          started.countDown();

          org.apache.accumulo.server.compaction.CompactionStats stat = compactor.get().call();
          TCompactionStats cs = new TCompactionStats();
          cs.setEntriesRead(stat.getEntriesRead());
          cs.setEntriesWritten(stat.getEntriesWritten());
          cs.setFileSize(stat.getFileSize());
          JOB_HOLDER.setStats(cs);

          LOG.info("Compaction completed successfully {} ", job.getExternalCompactionId());
          // Update state when completed
          TCompactionStatusUpdate update2 = new TCompactionStatusUpdate(TCompactionState.SUCCEEDED,
              "Compaction completed successfully", -1, -1, -1, this.getCompactionAge().toNanos());
          updateCompactionState(job, update2);
        } catch (FileCompactor.CompactionCanceledException cce) {
          LOG.debug("Compaction canceled {}", job.getExternalCompactionId());
          err.set(cce);
        } catch (Exception e) {
          KeyExtent fromThriftExtent = KeyExtent.fromThrift(job.getExtent());
          LOG.error("Compaction failed: id: {}, extent: {}", job.getExternalCompactionId(),
              fromThriftExtent, e);
          err.set(e);
        } finally {
          stopped.countDown();
          Preconditions.checkState(compactionRunning.compareAndSet(true, false));
        }
      }

      @Override
      public Duration getCompactionAge() {
        if (compactionStartTime == null) {
          // compaction hasn't started yet
          return Duration.ZERO;
        }
        return compactionStartTime.elapsed();
      }

    };

  }

  /**
   * @param extent the extent
   * @param file the file to read from
   * @param tableConf the table configuration
   * @param cryptoService the crypto service
   * @return an estimate of the number of key/value entries in the file that overlap the extent
   */
  private long estimateOverlappingEntries(KeyExtent extent, StoredTabletFile file,
      AccumuloConfiguration tableConf, CryptoService cryptoService) {
    FileOperations fileFactory = FileOperations.getInstance();
    FileSystem fs = getContext().getVolumeManager().getFileSystemByPath(file.getPath());

    try (FileSKVIterator reader =
        fileFactory.newReaderBuilder().forFile(file, fs, fs.getConf(), cryptoService)
            .withTableConfiguration(tableConf).dropCachesBehind().build()) {
      return reader.estimateOverlappingEntries(extent);
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  /**
   * Returns the number of seconds to wait in between progress checks based on input file sizes
   *
   * @param numBytes number of bytes in input file
   * @return number of seconds to wait between progress checks
   */
  static long calculateProgressCheckTime(long numBytes) {
    return Math.max(1, (numBytes / TEN_MEGABYTES));
  }

  protected Supplier<UUID> getNextId() {
    return UUID::randomUUID;
  }

  protected long getWaitTimeBetweenCompactionChecks(int numCompactors) {
    long minWait = getConfiguration().getTimeInMillis(Property.COMPACTOR_MIN_JOB_WAIT_TIME);
    // Aim for around 3 compactors checking in per min wait time.
    long sleepTime = numCompactors * minWait / 3;
    // Ensure a compactor waits at least the minimum time
    sleepTime = Math.max(minWait, sleepTime);
    // Ensure a sleeping compactor has a configurable max sleep time
    sleepTime = Math.min(getConfiguration().getTimeInMillis(Property.COMPACTOR_MAX_JOB_WAIT_TIME),
        sleepTime);
    // Add some random jitter to the sleep time, that averages out to sleep time. This will spread
    // compactors out evenly over time.
    sleepTime = (long) (.9 * sleepTime + sleepTime * .2 * RANDOM.get().nextDouble());
    LOG.trace("Sleeping {}ms based on {} compactors", sleepTime, numCompactors);
    return sleepTime;
  }

  protected Collection<Tag> getServiceTags(HostAndPort clientAddress) {
    return MetricsInfo.serviceTags(getContext().getInstanceName(), getApplicationName(),
        clientAddress, getResourceGroup());
  }

  private void performFailureProcessing(ConsecutiveErrorHistory errorHistory)
      throws InterruptedException {
    // consecutive failure processing
    final long totalFailures = errorHistory.getTotalFailures();
    if (totalFailures > 0) {
      LOG.warn("This Compactor has had {} consecutive failures. Failures: {}", totalFailures,
          errorHistory.toString()); // ErrorHistory.toString not invoked without .toString
      final long failureThreshold =
          getConfiguration().getCount(Property.COMPACTOR_FAILURE_TERMINATION_THRESHOLD);
      if (failureThreshold > 0 && totalFailures >= failureThreshold) {
        LOG.error(
            "Consecutive failures ({}) has met or exceeded failure threshold ({}), exiting...",
            totalFailures, failureThreshold);
        terminated.incrementAndGet();
        throw new InterruptedException(
            "Consecutive failures has exceeded failure threshold, exiting...");
      }
      if (totalFailures
          >= getConfiguration().getCount(Property.COMPACTOR_FAILURE_BACKOFF_THRESHOLD)) {
        final long interval =
            getConfiguration().getTimeInMillis(Property.COMPACTOR_FAILURE_BACKOFF_INTERVAL);
        if (interval > 0) {
          final long max =
              getConfiguration().getTimeInMillis(Property.COMPACTOR_FAILURE_BACKOFF_RESET);
          final long backoffMS = Math.min(max, interval * totalFailures);
          LOG.warn(
              "Not starting next compaction for {}ms due to consecutive failures. Check the log and address any issues.",
              backoffMS);
          if (backoffMS == max) {
            errorHistory.clear();
          }
          Thread.sleep(backoffMS);
        } else if (interval == 0) {
          LOG.info(
              "This Compactor has had {} consecutive failures and failure backoff is disabled.",
              totalFailures);
          errorHistory.clear();
        }
      }
    }
  }

  @Override
  public void run() {

    try {
      startCompactorClientService();
    } catch (UnknownHostException e1) {
      throw new RuntimeException("Failed to start the compactor client service", e1);
    }
    final HostAndPort clientAddress = getAdvertiseAddress();

    try {
      announceExistence(clientAddress);
    } catch (KeeperException | InterruptedException e) {
      throw new RuntimeException("Error registering compactor in ZooKeeper", e);
    }
    this.getContext().setServiceLock(compactorLock);

    MetricsInfo metricsInfo = getContext().getMetricsInfo();

    metricsInfo.addMetricsProducers(this, pausedMetrics);
    metricsInfo.init(getServiceTags(clientAddress));

    var watcher = new CompactionWatcher(getConfiguration());
    var schedExecutor = getContext().getScheduledExecutor();

    startCancelChecker(schedExecutor,
        getConfiguration().getTimeInMillis(Property.COMPACTOR_CANCEL_CHECK_INTERVAL));

    LOG.info("Compactor started, waiting for work");
    try {

      final AtomicReference<Throwable> err = new AtomicReference<>();
      final LogSorter logSorter = new LogSorter(this);
      long nextSortLogsCheckTime = System.currentTimeMillis();

      while (!isShutdownRequested()) {
        if (Thread.currentThread().isInterrupted()) {
          LOG.info("Server process thread has been interrupted, shutting down");
          break;
        }
        try {
          // mark compactor as idle while not in the compaction loop
          updateIdleStatus(true);

          currentCompactionId.set(null);
          err.set(null);
          JOB_HOLDER.reset();

          if (System.currentTimeMillis() > nextSortLogsCheckTime) {
            // Attempt to process all existing log sorting work serially in this thread.
            // When no work remains, this call will return so that we can look for compaction
            // work.
            LOG.debug("Checking to see if any recovery logs need sorting");
            nextSortLogsCheckTime = logSorter.sortLogsIfNeeded();
          }

          performFailureProcessing(errorHistory);

          TExternalCompactionJob job;
          try {
            TNextCompactionJob next = getNextJob(getNextId());
            job = next.getJob();
            if (!job.isSetExternalCompactionId()) {
              LOG.trace("No external compactions in queue {}", this.getResourceGroup());
              UtilWaitThread.sleep(getWaitTimeBetweenCompactionChecks(next.getCompactorCount()));
              continue;
            }
            if (!job.getExternalCompactionId().equals(currentCompactionId.get().toString())) {
              throw new IllegalStateException("Returned eci " + job.getExternalCompactionId()
                  + " does not match supplied eci " + currentCompactionId.get());
            }
          } catch (RetriesExceededException e2) {
            LOG.warn("Retries exceeded getting next job. Retrying...");
            continue;
          }
          LOG.debug("Received next compaction job: {}", job);

          final LongAdder totalInputEntries = new LongAdder();
          final LongAdder totalInputBytes = new LongAdder();
          final CountDownLatch started = new CountDownLatch(1);
          final CountDownLatch stopped = new CountDownLatch(1);

          final FileCompactorRunnable fcr =
              createCompactionJob(job, totalInputEntries, totalInputBytes, started, stopped, err);

          final Thread compactionThread = Threads.createNonCriticalThread(
              "Compaction job for tablet " + job.getExtent().toString(), fcr);

          JOB_HOLDER.set(job, compactionThread, fcr.getFileCompactor());

          try {
            // mark compactor as busy while compacting
            updateIdleStatus(false);

            // Need to call FileCompactorRunnable.initialize after calling JOB_HOLDER.set
            fcr.initialize();

            compactionThread.start(); // start the compactionThread
            started.await(); // wait until the compactor is started
            final long inputEntries = totalInputEntries.sum();
            final long waitTime = calculateProgressCheckTime(totalInputBytes.sum());
            LOG.debug("Progress checks will occur every {} seconds", waitTime);
            String percentComplete = "unknown";

            while (!stopped.await(waitTime, TimeUnit.SECONDS)) {
              List<CompactionInfo> running =
                  org.apache.accumulo.server.compaction.FileCompactor.getRunningCompactions();
              if (!running.isEmpty()) {
                // Compaction has started. There should only be one in the list
                CompactionInfo info = running.get(0);
                if (info != null) {
                  final long entriesRead = info.getEntriesRead();
                  final long entriesWritten = info.getEntriesWritten();
                  if (inputEntries > 0) {
                    percentComplete = Float.toString((entriesRead / (float) inputEntries) * 100);
                  }
                  String message = String.format(
                      "Compaction in progress, read %d of %d input entries ( %s %s ), written %d entries",
                      entriesRead, inputEntries, percentComplete, "%", entriesWritten);
                  watcher.run();
                  try {
                    LOG.debug("Updating coordinator with compaction progress: {}.", message);
                    TCompactionStatusUpdate update = new TCompactionStatusUpdate(
                        TCompactionState.IN_PROGRESS, message, inputEntries, entriesRead,
                        entriesWritten, fcr.getCompactionAge().toNanos());
                    updateCompactionState(job, update);
                  } catch (RetriesExceededException e) {
                    LOG.warn("Error updating coordinator with compaction progress, error: {}",
                        e.getMessage());
                  }
                }
              } else {
                LOG.debug("Waiting on compaction thread to finish, but no RUNNING compaction");
              }
            }
            compactionThread.join();
            LOG.trace("Compaction thread finished.");
            // Run the watcher again to clear out the finished compaction and set the
            // stuck count to zero.
            watcher.run();

            if (err.get() != null) {
              // maybe the error occured because the table was deleted or something like that, so
              // force a cancel check to possibly reduce noise in the logs
              checkIfCanceled();
            }

            if (compactionThread.isInterrupted() || JOB_HOLDER.isCancelled()
                || (err.get() != null && err.get().getClass().equals(InterruptedException.class))) {
              LOG.warn("Compaction thread was interrupted, sending CANCELLED state");
              try {
                TCompactionStatusUpdate update =
                    new TCompactionStatusUpdate(TCompactionState.CANCELLED, "Compaction cancelled",
                        -1, -1, -1, fcr.getCompactionAge().toNanos());
                updateCompactionState(job, update);
                updateCompactionFailed(job, InterruptedException.class.getName());
                cancelled.incrementAndGet();
              } catch (RetriesExceededException e) {
                LOG.error("Error updating coordinator with compaction cancellation.", e);
              } finally {
                currentCompactionId.set(null);
              }
            } else if (err.get() != null) {
              final KeyExtent fromThriftExtent = KeyExtent.fromThrift(job.getExtent());
              try {
                LOG.info("Updating coordinator with compaction failure: id: {}, extent: {}",
                    job.getExternalCompactionId(), fromThriftExtent);
                TCompactionStatusUpdate update = new TCompactionStatusUpdate(
                    TCompactionState.FAILED, "Compaction failed due to: " + err.get().getMessage(),
                    -1, -1, -1, fcr.getCompactionAge().toNanos());
                updateCompactionState(job, update);
                updateCompactionFailed(job, err.get().getClass().getName());
                failed.incrementAndGet();
                errorHistory.addError(fromThriftExtent.tableId(), err.get());
              } catch (RetriesExceededException e) {
                LOG.error("Error updating coordinator with compaction failure: id: {}, extent: {}",
                    job.getExternalCompactionId(), fromThriftExtent, e);
              } finally {
                currentCompactionId.set(null);
              }
            } else {
              try {
                LOG.trace("Updating coordinator with compaction completion.");
                updateCompactionCompleted(job, JOB_HOLDER.getStats());
                completed.incrementAndGet();
                // job completed successfully, clear the error history
                errorHistory.clear();
              } catch (RetriesExceededException e) {
                LOG.error(
                    "Error updating coordinator with compaction completion, cancelling compaction.",
                    e);
                try {
                  cancel(job.getExternalCompactionId());
                } catch (TException e1) {
                  LOG.error("Error cancelling compaction.", e1);
                }
              } finally {
                currentCompactionId.set(null);
              }
            }
          } catch (RuntimeException e1) {
            LOG.error(
                "Compactor thread was interrupted waiting for compaction to start, cancelling job",
                e1);
            try {
              cancel(job.getExternalCompactionId());
            } catch (TException e2) {
              LOG.error("Error cancelling compaction.", e2);
            }
          } finally {
            currentCompactionId.set(null);

            // mark compactor as idle after compaction completes
            updateIdleStatus(true);

            // In the case where there is an error in the foreground code the background compaction
            // may still be running. Must cancel it before starting another iteration of the loop to
            // avoid multiple threads updating shared state.
            while (compactionThread.isAlive()) {
              compactionThread.interrupt();
              compactionThread.join(1000);
            }
          }
        } catch (InterruptedException e) {
          LOG.info("Interrupt Exception received, shutting down");
          gracefulShutdown(getContext().rpcCreds());
        }
      } // end while
    } catch (Exception e) {
      LOG.error("Unhandled error occurred in Compactor", e);
    } finally {
      // Shutdown local thrift server
      LOG.debug("Stopping Thrift Servers");
      if (getThriftServer() != null) {
        getThriftServer().stop();
      }

      try {
        LOG.debug("Closing filesystems");
        VolumeManager mgr = getContext().getVolumeManager();
        if (null != mgr) {
          mgr.close();
        }
      } catch (IOException e) {
        LOG.warn("Failed to close filesystem : {}", e.getMessage(), e);
      }

      getContext().getLowMemoryDetector().logGCInfo(getConfiguration());
      super.close();
      getShutdownComplete().set(true);
      LOG.info("stop requested. exiting ... ");
      try {
        if (null != compactorLock) {
          compactorLock.unlock();
        }
      } catch (Exception e) {
        LOG.warn("Failed to release compactor lock", e);
      }
    }

  }

  public static void main(String[] args) throws Exception {
    try (Compactor compactor = new Compactor(new ConfigOpts(), args)) {
      compactor.runServer();
    }
  }

  @Override
  public List<ActiveCompaction> getActiveCompactions(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    if (!getContext().getSecurityOperation().canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    List<CompactionInfo> compactions =
        org.apache.accumulo.server.compaction.FileCompactor.getRunningCompactions();
    List<ActiveCompaction> ret = new ArrayList<>(compactions.size());

    for (CompactionInfo compactionInfo : compactions) {
      ret.add(compactionInfo.toThrift());
    }

    return ret;
  }

  /**
   * Called by a CompactionCoordinator to get the running compaction
   *
   * @param tinfo trace info
   * @param credentials caller credentials
   * @return current compaction job or empty compaction job is none running
   */
  @Override
  public TExternalCompactionJob getRunningCompaction(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!getContext().getSecurityOperation().canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    // Return what is currently running, does not wait for jobs in the process of reserving. This
    // method is called by a coordinator starting up to determine what is currently running on all
    // compactors.

    TExternalCompactionJob job = null;
    synchronized (JOB_HOLDER) {
      job = JOB_HOLDER.getJob();
    }

    if (null == job) {
      return new TExternalCompactionJob();
    } else {
      return job;
    }
  }

  @Override
  public String getRunningCompactionId(TInfo tinfo, TCredentials credentials)
      throws ThriftSecurityException, TException {
    // do not expect users to call this directly, expect other tservers to call this method
    if (!getContext().getSecurityOperation().canPerformSystemActions(credentials)) {
      throw new AccumuloSecurityException(credentials.getPrincipal(),
          SecurityErrorCode.PERMISSION_DENIED).asThriftException();
    }

    // Any returned id must cover the time period from before a job is reserved until after it
    // commits. This method is called to detect dead compactions and depends on this behavior.
    // For the purpose of detecting dead compactions its ok if ids are returned that never end up
    // being related to a running compaction.
    ExternalCompactionId eci = currentCompactionId.get();
    if (null == eci) {
      return "";
    } else {
      return eci.canonical();
    }
  }

  @Override
  public ServiceLock getLock() {
    return compactorLock;
  }
}
