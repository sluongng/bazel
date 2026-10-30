// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.analysis.AspectValue;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema;
import com.google.devtools.build.lib.packages.StarlarkInfoWithSchema;
import com.google.devtools.build.lib.server.IdleTask;
import com.google.devtools.build.lib.server.IdleTaskException;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ProviderSnapshot;
import com.google.devtools.build.skyframe.InMemoryGraph;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/** Coordinates same-daemon, quiescent provider storage without changing the Skyframe graph. */
final class AnalysisHibernation implements AutoCloseable {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(5);
  private final Object stateLock = new Object();
  private final LongSupplier nanoTime;
  @Nullable private ProviderSnapshot snapshot;
  @Nullable private Thread activeThread;
  @Nullable private Path directory;
  private boolean closed;
  private boolean limitExceeded;
  private boolean retryDelayed;
  private long lastFailureNanos;
  @Nullable private String warning;

  AnalysisHibernation() {
    this(System::nanoTime);
  }

  AnalysisHibernation(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  IdleTask idleTask(
      InMemoryGraph graph, Path outputBase, Duration delay, ProviderSnapshot.Limits limits) {
    return new IdleTask() {
      @Override
      public String displayName() {
        return "Analysis provider hibernation";
      }

      @Override
      public Duration delay() {
        return delay;
      }

      @Override
      public void run() throws InterruptedException, IdleTaskException {
        if (!beginOperation(false)) {
          return;
        }
        long start = System.nanoTime();
        try {
          List<TransitiveInfoProviderMapImpl> maps = select(graph, limits.maxObjects());
          if (maps.isEmpty()) {
            return;
          }
          Path location = prepareDirectory(outputBase);
          ProviderSnapshot written = ProviderSnapshot.createForHibernation(location, maps, limits);
          if (written == null) {
            synchronized (stateLock) {
              retryDelayed = true;
              lastFailureNanos = nanoTime.getAsLong();
              warning =
                  "Idle analysis cache skipped: copied payload is too small relative to resident"
                      + " snapshot bookkeeping. Further attempts will wait at least five minutes.";
            }
            return;
          }
          synchronized (stateLock) {
            snapshot = written;
            retryDelayed = false;
            warning = null;
          }
          logger.atInfo().log(
              "[Analysis hibernation] stored %d maps, %d bytes in %d ms",
              written.mapCount(), written.storedBytes(), elapsedMillis(start));
          if (!Thread.currentThread().isInterrupted()) {
            long gcStart = System.nanoTime();
            System.gc();
            logger.atInfo().log(
                "[Analysis hibernation] post-detach GC in %d ms", elapsedMillis(gcStart));
            checkInterrupted();
          }
        } catch (VirtualMachineError | ThreadDeath fatal) {
          throw BugReport.handleCrash(fatal);
        } catch (Throwable failure) {
          if (Thread.currentThread().isInterrupted() || causedByInterrupt(failure)) {
            throw new InterruptedException("analysis hibernation interrupted");
          }
          var limitFailure = findLimitFailure(failure);
          synchronized (stateLock) {
            if (limitFailure != null) {
              limitExceeded = true;
              warning =
                  "Idle analysis cache skipped: "
                      + limitFailure.getMessage()
                      + ". Further attempts are disabled until the evaluator is reset.";
            } else {
              retryDelayed = true;
              lastFailureNanos = nanoTime.getAsLong();
              warning =
                  "Idle analysis cache unavailable; further attempts will wait at least five"
                      + " minutes.";
            }
          }
          // An unchecked exception escaping an IdleTask would poison the next busy() transition.
          throw new IdleTaskException(new Exception("analysis hibernation failed", failure));
        } finally {
          endOperation();
        }
      }
    };
  }

  @Nullable
  String takeWarning() {
    synchronized (stateLock) {
      String result = warning;
      warning = null;
      return result;
    }
  }

  boolean isHibernated() {
    synchronized (stateLock) {
      return snapshot != null;
    }
  }

  /** Called before any command parser/environment/graph access, under command exclusion. */
  boolean restore() throws IOException, SerializationException, InterruptedException {
    if (!beginOperation(true)) {
      return false;
    }
    ProviderSnapshot current;
    synchronized (stateLock) {
      current = snapshot;
    }
    if (current == null) {
      endOperation();
      return false;
    }
    long start = System.nanoTime();
    try {
      current.restore();
      synchronized (stateLock) {
        snapshot = null;
      }
      // The graph is fully resident again. File cleanup must not turn success into a cold reset.
      closeSnapshot(current);
      logger.atInfo().log(
          "[Analysis hibernation] restored %d maps, %d bytes in %d ms",
          current.mapCount(), current.storedBytes(), elapsedMillis(start));
      return true;
    } catch (IOException | SerializationException | RuntimeException failure) {
      if (Thread.currentThread().isInterrupted() || causedByInterrupt(failure)) {
        // No pointer changes occur before the decoder's final interruption check. Retry later.
        throw new InterruptedException("analysis cache restoration interrupted");
      }
      throw failure;
    } finally {
      endOperation();
    }
  }

  /** Requires no command/evaluator operation in progress. Keeps the coordinator usable. */
  void discard() {
    ProviderSnapshot previous;
    synchronized (stateLock) {
      previous = snapshot;
      snapshot = null;
      limitExceeded = false;
      retryDelayed = false;
      warning = null;
    }
    closeSnapshot(previous);
  }

  /** Interrupts an idle writer without waiting on I/O; that operation owns deferred cleanup. */
  @Override
  public void close() {
    ProviderSnapshot previous;
    Path previousDirectory;
    synchronized (stateLock) {
      closed = true;
      if (activeThread != null) {
        activeThread.interrupt();
        return;
      }
      previous = snapshot;
      snapshot = null;
      previousDirectory = directory;
      directory = null;
    }
    closeSnapshot(previous);
    deleteDirectory(previousDirectory);
  }

  private boolean beginOperation(boolean restoring) {
    synchronized (stateLock) {
      if (closed
          || activeThread != null
          || (!restoring
              && (snapshot != null
                  || limitExceeded
                  || (retryDelayed
                      && nanoTime.getAsLong() - lastFailureNanos
                          < FAILURE_RETRY_DELAY.toNanos())))) {
        return false;
      }
      activeThread = Thread.currentThread();
      return true;
    }
  }

  private void endOperation() {
    ProviderSnapshot previous = null;
    Path previousDirectory = null;
    synchronized (stateLock) {
      activeThread = null;
      if (closed) {
        previous = snapshot;
        snapshot = null;
        previousDirectory = directory;
        directory = null;
      }
    }
    closeSnapshot(previous);
    deleteDirectory(previousDirectory);
  }

  private Path prepareDirectory(Path outputBase) throws IOException {
    synchronized (stateLock) {
      if (directory != null) {
        return directory;
      }
    }
    Path root = outputBase.resolve("server").resolve("provider-hibernation");
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root)) {
      throw new IOException("invalid hibernation directory");
    }
    // The output-base server lock excludes another daemon. Never import stale generation data.
    try (var sessions = Files.newDirectoryStream(root, "session-*")) {
      for (Path session : sessions) {
        checkInterrupted();
        if (Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) {
          try (var files = Files.newDirectoryStream(session)) {
            for (Path file : files) {
              checkInterrupted();
              if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(file);
              }
            }
          }
          Files.delete(session);
        }
      }
    }
    Path created = Files.createTempDirectory(root, "session-");
    synchronized (stateLock) {
      directory = created;
    }
    return created;
  }

  private static List<TransitiveInfoProviderMapImpl> select(InMemoryGraph graph, int maximum)
      throws IOException {
    var maps = new ArrayList<TransitiveInfoProviderMapImpl>();
    var seen = new IdentityHashMap<TransitiveInfoProviderMapImpl, Boolean>();
    for (var entry : graph.getAllNodeEntries()) {
      checkInterrupted();
      if (!entry.isDone() || entry.getErrorInfo() != null) {
        continue;
      }
      var value = entry.getValue();
      TransitiveInfoProviderMap providers;
      if (value instanceof RuleConfiguredTargetValue rule
          && !rule.isCleared()
          && rule.getConfiguredTarget() instanceof RuleConfiguredTarget target) {
        providers = target.getProvidersForMetrics();
      } else if (value instanceof AspectValue aspect && !aspect.isCleared()) {
        providers = aspect.getProviders();
      } else {
        continue;
      }
      if (providers instanceof TransitiveInfoProviderMapImpl map
          && map.size() > 0
          && hasCopyableProvider(map)
          && seen.put(map, true) == null) {
        if (maps.size() >= maximum) {
          throw new ProviderSnapshot.LimitExceededException("too many provider maps");
        }
        maps.add(map);
      }
    }
    return maps;
  }

  private static boolean hasCopyableProvider(TransitiveInfoProviderMapImpl map) throws IOException {
    for (int i = 0; i < map.size(); i++) {
      checkInterrupted();
      Object value = map.valueAt(i);
      Class<?> type = value.getClass();
      if (type == StarlarkInfoNoSchema.class
          || (StarlarkInfoWithSchema.class.isAssignableFrom(type)
              && type.getEnclosingClass() == StarlarkInfoWithSchema.class)) {
        return true;
      }
    }
    return false;
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException();
    }
  }

  @Nullable
  private static ProviderSnapshot.LimitExceededException findLimitFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ProviderSnapshot.LimitExceededException limit) {
        return limit;
      }
    }
    return null;
  }

  private static boolean causedByInterrupt(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof InterruptedException
          || current instanceof InterruptedIOException
          || current instanceof java.nio.channels.ClosedByInterruptException) {
        return true;
      }
    }
    return false;
  }

  private static long elapsedMillis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static void closeSnapshot(@Nullable ProviderSnapshot snapshot) {
    if (snapshot != null) {
      try {
        snapshot.close();
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Snapshot cleanup failed");
      }
    }
  }

  private static void deleteDirectory(@Nullable Path directory) {
    if (directory != null) {
      try {
        Files.deleteIfExists(directory);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Snapshot directory cleanup failed");
      }
    }
  }
}
