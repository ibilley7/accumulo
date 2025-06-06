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
package org.apache.accumulo.tserver;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.logging.TabletLogger;
import org.apache.accumulo.core.manager.thrift.TabletLoadState;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.Location;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.server.manager.state.Assignment;
import org.apache.accumulo.server.manager.state.TabletStateStore;
import org.apache.accumulo.tserver.TabletServerResourceManager.TabletResourceManager;
import org.apache.accumulo.tserver.managermessage.TabletStatusMessage;
import org.apache.accumulo.tserver.tablet.Tablet;
import org.apache.accumulo.tserver.tablet.Tablet.RefreshPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AssignmentHandler implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(AssignmentHandler.class);
  private static final String METADATA_ISSUE = "Saw metadata issue when loading tablet : ";
  private final KeyExtent extent;
  private final int retryAttempt;
  private final TabletServer server;

  public AssignmentHandler(TabletServer server, KeyExtent extent) {
    this(server, extent, 0);
  }

  public AssignmentHandler(TabletServer server, KeyExtent extent, int retryAttempt) {
    this.server = server;
    this.extent = extent;
    this.retryAttempt = retryAttempt;
  }

  @Override
  public void run() {
    synchronized (server.unopenedTablets) {
      synchronized (server.openingTablets) {
        synchronized (server.onlineTablets) {
          // nothing should be moving between sets, do a sanity
          // check
          Set<KeyExtent> unopenedOverlapping =
              KeyExtent.findOverlapping(extent, server.unopenedTablets);
          Set<KeyExtent> openingOverlapping =
              KeyExtent.findOverlapping(extent, server.openingTablets);
          Set<KeyExtent> onlineOverlapping =
              KeyExtent.findOverlapping(extent, server.onlineTablets.snapshot());

          if (openingOverlapping.contains(extent) || onlineOverlapping.contains(extent)) {
            return;
          }

          if (!unopenedOverlapping.contains(extent)) {
            log.info("assignment {} no longer in the unopened set", extent);
            return;
          }

          if (unopenedOverlapping.size() != 1 || !openingOverlapping.isEmpty()
              || !onlineOverlapping.isEmpty()) {
            throw new IllegalStateException(
                "overlaps assigned " + extent + " " + !server.unopenedTablets.contains(extent) + " "
                    + unopenedOverlapping + " " + openingOverlapping + " " + onlineOverlapping);
          }
        }

        server.unopenedTablets.remove(extent);
        server.openingTablets.add(extent);
      }
    }

    // check Metadata table before accepting assignment
    TabletMetadata tabletMetadata = null;
    boolean canLoad = false;
    try {
      tabletMetadata = server.getContext().getAmple().readTablet(extent);

      canLoad = checkTabletMetadata(extent, server.getTabletSession(), tabletMetadata);
    } catch (Exception e) {
      synchronized (server.openingTablets) {
        server.openingTablets.remove(extent);
        server.openingTablets.notifyAll();
      }
      TabletLogger.tabletLoadFailed(extent, e);
      server.enqueueManagerMessage(new TabletStatusMessage(TabletLoadState.LOAD_FAILURE, extent));
      throw new RuntimeException(e);
    }

    if (!canLoad) {
      log.debug("Reporting tablet {} assignment failure: unable to verify Tablet Information",
          extent);
      synchronized (server.openingTablets) {
        server.openingTablets.remove(extent);
        server.openingTablets.notifyAll();
      }
      server.enqueueManagerMessage(new TabletStatusMessage(TabletLoadState.LOAD_FAILURE, extent));
      return;
    }

    Tablet tablet = null;
    boolean successful = false;

    try (var recoveryMemory = server.acquireRecoveryMemory(tabletMetadata)) {
      TabletResourceManager trm = server.resourceManager.createTabletResourceManager(extent,
          server.getTableConfiguration(extent));

      tablet = new Tablet(server, extent, trm, tabletMetadata);
      // If a minor compaction starts after a tablet opens, this indicates a log recovery
      // occurred. This recovered data must be minor compacted.
      // There are three reasons to wait for this minor compaction to finish before placing the
      // tablet in online tablets.
      //
      // 1) The log recovery code does not handle data written to the tablet on multiple tablet
      // servers.
      // 2) The log recovery code does not block if memory is full. Therefore recovering lots of
      // tablets that use a lot of memory could run out of memory.
      // 3) The minor compaction finish event did not make it to the logs (the file will be in
      // metadata, preventing replay of compacted data)... but do not
      // want a majc to wipe the file out from metadata and then have another process failure...
      // this could cause duplicate data to replay.
      if (tablet.getNumEntriesInMemory() > 0
          && !tablet.minorCompactNow(MinorCompactionReason.RECOVERY)) {
        throw new RuntimeException("Minor compaction after recovery fails for " + extent);
      }

      Assignment assignment =
          new Assignment(extent, server.getTabletSession(), tabletMetadata.getLast());
      TabletStateStore.setLocation(server.getContext(), assignment);

      // refresh the tablet metadata after setting the location (See #3358)
      tablet.refreshMetadata(RefreshPurpose.LOAD);

      synchronized (server.openingTablets) {
        synchronized (server.onlineTablets) {
          server.openingTablets.remove(extent);
          server.onlineTablets.put(extent, tablet);
          server.openingTablets.notifyAll();
          server.recentlyUnloadedCache.remove(tablet.getExtent());
        }
      }

      tablet = null; // release this reference
      successful = true;
    } catch (Exception e) {
      TabletLogger.tabletLoadFailed(extent, e);
    }

    if (successful) {
      server.enqueueManagerMessage(new TabletStatusMessage(TabletLoadState.LOADED, extent));
    } else {
      synchronized (server.unopenedTablets) {
        synchronized (server.openingTablets) {
          server.openingTablets.remove(extent);
          server.unopenedTablets.add(extent);
          server.openingTablets.notifyAll();
        }
      }
      log.warn("failed to open tablet {} reporting failure to manager", extent);
      server.enqueueManagerMessage(new TabletStatusMessage(TabletLoadState.LOAD_FAILURE, extent));
      long reschedule = Math.min((1L << Math.min(32, retryAttempt)) * 1000, MINUTES.toMillis(10));
      log.warn(String.format("rescheduling tablet load in %.2f seconds", reschedule / 1000.));
      ThreadPools.watchCriticalScheduledTask(
          this.server.getContext().getScheduledExecutor().schedule(new Runnable() {
            @Override
            public void run() {
              log.info("adding tablet {} back to the assignment pool (retry {})", extent,
                  retryAttempt);
              AssignmentHandler handler = new AssignmentHandler(server, extent, retryAttempt + 1);
              if (extent.isMeta()) {
                if (extent.isRootTablet()) {
                  Threads.createNonCriticalThread("Root tablet assignment retry", handler).start();
                } else {
                  server.resourceManager.addMetaDataAssignment(extent, log, handler);
                }
              } else {
                server.resourceManager.addAssignment(extent, log, handler);
              }
            }
          }, reschedule, TimeUnit.MILLISECONDS));
    }
  }

  public static boolean checkTabletMetadata(KeyExtent extent, TServerInstance instance,
      TabletMetadata meta) throws AccumuloException {
    return checkTabletMetadata(extent, instance, meta, false);
  }

  public static boolean checkTabletMetadata(KeyExtent extent, TServerInstance instance,
      TabletMetadata meta, boolean ignoreLocationCheck) throws AccumuloException {

    if (meta == null) {
      log.info(METADATA_ISSUE + "{}, its metadata was not found.", extent);
      return false;
    }

    if (!meta.sawPrevEndRow()) {
      throw new AccumuloException(METADATA_ISSUE + "metadata entry does not have prev row ("
          + meta.getTableId() + " " + meta.getEndRow() + ")");
    }

    if (!extent.equals(meta.getExtent())) {
      log.info(METADATA_ISSUE + "tablet extent mismatch {} {}", extent, meta.getExtent());
      return false;
    }

    if (meta.getDirName() == null) {
      throw new AccumuloException(
          METADATA_ISSUE + "metadata entry does not have directory (" + meta.getExtent() + ")");
    }

    if (meta.getTime() == null && !extent.equals(RootTable.EXTENT)) {
      throw new AccumuloException(
          METADATA_ISSUE + "metadata entry does not have time (" + meta.getExtent() + ")");
    }

    Location loc = meta.getLocation();

    if (!ignoreLocationCheck && (loc == null || loc.getType() != TabletMetadata.LocationType.FUTURE
        || !instance.equals(loc.getServerInstance()))) {
      log.info(METADATA_ISSUE + "Unexpected location {} {}", extent, loc);
      return false;
    }

    if (meta.getOperationId() != null && meta.getLocation() == null) {
      log.info(METADATA_ISSUE + "metadata entry has a FATE operation id {} {} {}", extent, loc,
          meta.getOperationId());
      return false;
    }

    return true;
  }
}
