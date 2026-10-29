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
package com.google.devtools.build.lib.remote;

import static com.google.devtools.build.lib.remote.util.Futures.getFromFuture;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.server.IdleTask;
import com.google.devtools.build.lib.server.IdleTaskException;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Discards materialized output bytes without discarding their persistent remote metadata. */
public final class OutputGarbageCollector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final int DIGEST_BATCH_SIZE = 1024;

  private final Clock clock;

  public OutputGarbageCollector(Clock clock) {
    this.clock = clock;
  }

  /**
   * Creates an idle task retaining the cache connection until the task is closed.
   *
   * <p>The owner must close the task when the server becomes busy or shuts down.
   */
  public Task createIdleTask(
      Supplier<MemoizingEvaluator> evaluatorSupplier,
      Supplier<ActionCache> actionCacheSupplier,
      CombinedCache cache,
      Path execRoot,
      RemoteOutputChecker checker,
      Duration minAge,
      Duration idleDelay,
      boolean dryRun,
      RemoteActionExecutionContext context) {
    return new Task(
        evaluatorSupplier,
        actionCacheSupplier,
        cache,
        execRoot,
        checker,
        minAge,
        idleDelay,
        dryRun,
        context);
  }

  /** An interruptible, repeatable output collection. */
  public final class Task implements IdleTask, AutoCloseable {
    private final Supplier<MemoizingEvaluator> evaluatorSupplier;
    private final Supplier<ActionCache> actionCacheSupplier;
    private final CombinedCache cache;
    private final Path execRoot;
    private final RemoteOutputChecker checker;
    private final Duration minAge;
    private final Duration idleDelay;
    private final boolean dryRun;
    private final RemoteActionExecutionContext context;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Task(
        Supplier<MemoizingEvaluator> evaluatorSupplier,
        Supplier<ActionCache> actionCacheSupplier,
        CombinedCache cache,
        Path execRoot,
        RemoteOutputChecker checker,
        Duration minAge,
        Duration idleDelay,
        boolean dryRun,
        RemoteActionExecutionContext context) {
      this.evaluatorSupplier = evaluatorSupplier;
      this.actionCacheSupplier = actionCacheSupplier;
      this.cache = cache.retain();
      this.execRoot = execRoot.forHostFileSystem();
      this.checker = checker;
      this.minAge = minAge;
      this.idleDelay = idleDelay;
      this.dryRun = dryRun;
      this.context = context;
    }

    @Override
    public String displayName() {
      return "Output garbage collector";
    }

    @Override
    public Duration delay() {
      return idleDelay;
    }

    @Override
    public Duration repeatDelay() {
      Duration interval =
          idleDelay.compareTo(Duration.ofMinutes(5)) < 0 ? idleDelay : Duration.ofMinutes(5);
      return interval.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : interval;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        cache.release();
      }
    }

    @Override
    public void run() throws IdleTaskException, InterruptedException {
      var evaluator = evaluatorSupplier.get();
      Set<PathFragment> removedPaths = new HashSet<>();
      long count = 0;
      long bytes = 0;
      try {
        if (closed.get()) {
          return;
        }
        ActionCache actionCache = actionCacheSupplier.get();
        if (actionCache == null) {
          return;
        }
        // Compact action-cache access timestamps have minute resolution. Treat their upper
        // bound as the use time so rounding never makes a freshly used output prematurely old.
        Instant cutoff =
            minAge.isZero() ? Instant.MAX : clock.now().minus(minAge).minus(Duration.ofMinutes(1));
        var entries = actionCache.getEntries();
        var ownership = new OwnershipIndex(entries);
        ownership.protectLocalOutputs(evaluator);
        boolean saved = false;
        for (var item : entries.entrySet()) {
          var timedEntry = item.getValue();
          checkInterrupted();
          if (timedEntry.lastAccessTime().isAfter(cutoff)
              || timedEntry.entry().isCorrupted()
              || timedEntry.entry().getOutputPaths() == null) {
            continue;
          }
          for (Group group : collectGroups(timedEntry.entry())) {
            checkInterrupted();
            if (ownership.hasConflictingOwner(
                group.execPath(), item.getKey(), timedEntry.lastAccessTime())) {
              continue;
            }
            Map<PathFragment, FileContentsProxy> localFiles = inspectLocalFiles(group);
            if (localFiles == null || localFiles.isEmpty()) {
              continue;
            }
            if (dryRun) {
              count++;
              bytes += group.localSize(localFiles.keySet());
              continue;
            }
            if (!hasBacking(group)) {
              continue;
            }
            // The bytes must never disappear before their metadata is durable. This also flushes
            // journal updates made by the just-finished build.
            if (!saved) {
              actionCache.save();
              saved = true;
            }
            checkInterrupted();
            if (!unchanged(group, localFiles)) {
              continue;
            }
            // Invalidate even if removal is interrupted or fails after changing only part of a
            // tree. The persistent action cache remains intact, so reevaluation can rematerialize
            // outputs directly rather than looking up or executing their producing remote action.
            removedPaths.add(group.execPath());
            delete(group);
            count++;
            bytes += group.localSize(localFiles.keySet());
          }
        }
        logger.atInfo().log(
            "Output garbage collector: %s artifacts, %s bytes%s",
            count, bytes, dryRun ? " (dry run)" : "");
      } catch (IOException e) {
        throw new IdleTaskException(e);
      } finally {
        if (!removedPaths.isEmpty()) {
          evaluator.delete(
              (key, value) ->
                  key.functionName().equals(SkyFunctions.ACTION_EXECUTION)
                      && value instanceof ActionExecutionValue actionValue
                      && hasRemovedOutput(actionValue, removedPaths));
        }
      }
    }

    private List<Group> collectGroups(ActionCache.Entry entry) throws InterruptedException {
      List<Group> groups = new ArrayList<>();
      for (var file : entry.getOutputFiles().entrySet()) {
        checkInterrupted();
        PathFragment path = PathFragment.create(file.getKey());
        if (eligible(path, null, file.getValue())) {
          groups.add(new Group(path, false, Map.of(path, file.getValue())));
        }
      }
      for (var treeEntry : entry.getOutputTrees().entrySet()) {
        checkInterrupted();
        PathFragment path = PathFragment.create(treeEntry.getKey());
        var tree = treeEntry.getValue();
        if (!safeExecPath(path)
            || checker.isProtectedOutput(path, null)
            || tree.resolvedPath().isPresent()
            || tree.archivedFileValue().isPresent()
            || tree.childValues().isEmpty()) {
          continue;
        }
        Map<PathFragment, FileArtifactValue> files = new HashMap<>();
        boolean eligibleTree = true;
        for (var child : tree.childValues().entrySet()) {
          checkInterrupted();
          PathFragment relative = PathFragment.create(child.getKey());
          if (relative.isAbsolute() || relative.containsUplevelReferences()) {
            eligibleTree = false;
            break;
          }
          PathFragment childPath = path.getRelative(relative);
          if (!eligible(childPath, path, child.getValue())) {
            eligibleTree = false;
            break;
          }
          files.put(childPath, child.getValue());
        }
        if (eligibleTree) {
          groups.add(new Group(path, true, files));
        }
      }
      return groups;
    }

    private boolean eligible(
        PathFragment path, @Nullable PathFragment treeRoot, FileArtifactValue metadata) {
      return safeExecPath(path)
          && !checker.isProtectedOutput(path, treeRoot)
          && metadata.isRemote()
          && !metadata.isInMemoryOutput()
          && metadata.getResolvedPath() == null
          && metadata.getType() == FileStateType.REGULAR_FILE
          && metadata.getDigest() != null;
    }

    /**
     * Only derived output paths, not source files or external repository contents, are collected.
     */
    private boolean safeExecPath(PathFragment path) {
      return !path.isAbsolute()
          && !path.containsUplevelReferences()
          && path.segmentCount() > 1
          && (path.getSegment(0).equals("bazel-out") || path.getSegment(0).equals("blaze-out"));
    }

    /** Returns null if an output was externally modified or traverses a symlink. */
    @Nullable
    private Map<PathFragment, FileContentsProxy> inspectLocalFiles(Group group)
        throws IOException, InterruptedException {
      Path root = execRoot.getRelative(group.execPath());
      if (!safeAncestors(root)) {
        return null;
      }
      var rootStat = root.statIfFound(Symlinks.NOFOLLOW);
      if (rootStat == null) {
        return Map.of();
      }
      Map<PathFragment, FileContentsProxy> localFiles = new HashMap<>();
      if (!group.tree()) {
        var proxy = matchingLocalFile(root, rootStat, group.files().get(group.execPath()));
        if (proxy == null) {
          return null;
        }
        localFiles.put(group.execPath(), proxy);
        return localFiles;
      }
      if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
        return null;
      }
      ArrayDeque<Path> directories = new ArrayDeque<>();
      directories.add(root);
      while (!directories.isEmpty()) {
        checkInterrupted();
        Path dir = directories.removeFirst();
        var dirStat = dir.statIfFound(Symlinks.NOFOLLOW);
        if (!safeAncestors(dir)
            || dirStat == null
            || !dirStat.isDirectory()
            || dirStat.isSymbolicLink()) {
          return null;
        }
        for (Dirent dirent : dir.readdir(Symlinks.NOFOLLOW)) {
          checkInterrupted();
          Path child = dir.getChild(dirent.getName());
          if (dirent.getType() == Dirent.Type.DIRECTORY) {
            directories.add(child);
          } else if (dirent.getType() == Dirent.Type.FILE) {
            PathFragment execPath = child.relativeTo(execRoot);
            FileArtifactValue metadata = group.files().get(execPath);
            if (metadata == null) {
              return null;
            }
            var stat = child.statIfFound(Symlinks.NOFOLLOW);
            var proxy = stat == null ? null : matchingLocalFile(child, stat, metadata);
            if (proxy == null) {
              return null;
            }
            localFiles.put(execPath, proxy);
          } else {
            return null;
          }
        }
      }
      return localFiles;
    }

    private boolean safeAncestors(Path path) throws IOException {
      for (Path parent = path.getParentDirectory();
          parent != null;
          parent = parent.getParentDirectory()) {
        if (!parent.startsWith(execRoot)) {
          return false;
        }
        if (parent.isSymbolicLink()) {
          return false;
        }
        if (parent.equals(execRoot)) {
          return true;
        }
      }
      return false;
    }

    @Nullable
    private FileContentsProxy matchingLocalFile(
        Path path, FileStatus stat, FileArtifactValue metadata)
        throws IOException, InterruptedException {
      if (!stat.isFile() || stat.isSymbolicLink() || stat.getSize() != metadata.getSize()) {
        return null;
      }
      var proxy = FileContentsProxy.create(stat);
      if (proxy.equals(metadata.getContentsProxy())) {
        return proxy;
      }
      byte[] digest = path.getFastDigest();
      if (digest == null) {
        // A single very large artifact must not prevent the next command from interrupting GC.
        var hasher = path.getFileSystem().getDigestFunction().getHashFunction().newHasher();
        try (var input = path.getInputStream()) {
          byte[] buffer = new byte[128 * 1024];
          int read;
          while ((read = input.read(buffer)) != -1) {
            checkInterrupted();
            hasher.putBytes(buffer, 0, read);
          }
        }
        digest = hasher.hash().asBytes();
      }
      if (!Arrays.equals(digest, metadata.getDigest())) {
        return null;
      }
      metadata.setContentsProxy(proxy);
      return proxy;
    }

    private boolean unchanged(Group group, Map<PathFragment, FileContentsProxy> expected)
        throws IOException, InterruptedException {
      // Rewalk trees as well as restatting files, to avoid discarding newly added untracked files.
      var actual = inspectLocalFiles(group);
      return actual != null && actual.equals(expected);
    }

    private boolean hasBacking(Group group) throws IOException, InterruptedException {
      Set<Digest> uniqueDigests = new HashSet<>();
      for (var metadata : group.files().values()) {
        checkInterrupted();
        uniqueDigests.add(DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize()));
      }
      List<Digest> digests = new ArrayList<>(uniqueDigests);
      for (List<Digest> batch : Iterables.partition(digests, DIGEST_BATCH_SIZE)) {
        checkInterrupted();
        ImmutableSet<Digest> missing =
            cache.remoteCacheClient != null && context.getWriteCachePolicy().allowRemoteCache()
                ? getFromFuture(cache.findMissingDigests(context, batch))
                : ImmutableSet.copyOf(batch);
        for (Digest digest : missing) {
          if (digest.getSizeBytes() == 0) {
            continue;
          }
          if (cache.diskCacheClient == null || !context.getReadCachePolicy().allowDiskCache()) {
            return false;
          }
          Path cachedPath = cache.diskCacheClient.toPath(digest, Store.CAS);
          var stat = cachedPath.statIfFound(Symlinks.NOFOLLOW);
          if (stat == null
              || !stat.isFile()
              || stat.isSymbolicLink()
              || stat.getSize() != digest.getSizeBytes()
              || !cache.diskCacheClient.refresh(cachedPath)) {
            return false;
          }
        }
      }
      return true;
    }

    private void delete(Group group) throws IOException, InterruptedException {
      checkInterrupted();
      Path path = execRoot.getRelative(group.execPath());
      if (!safeAncestors(path)) {
        return;
      }
      Path parent = path.getParentDirectory();
      boolean writable = parent.isWritable();
      try {
        if (!writable) {
          parent.setWritable(true);
        }
        if (group.tree()) {
          path.deleteTree();
        } else {
          path.delete();
        }
      } finally {
        if (!writable) {
          parent.setWritable(false);
        }
      }
    }
  }

  /**
   * Tracks complete output ownership, including local-only files. Digest equality is not enough: a
   * stale remote tree can have been replaced by a local action writing one of its children.
   */
  private static final class OwnershipIndex {
    private final NavigableMap<String, List<Ownership>> paths = new TreeMap<>();
    @Nullable private Instant newestUnknownOwner;

    OwnershipIndex(Map<String, ActionCache.EntryWithAccessTime> entries)
        throws InterruptedException {
      for (var item : entries.entrySet()) {
        checkInterrupted();
        var timedEntry = item.getValue();
        if (timedEntry.entry().isCorrupted()) {
          continue;
        }
        var outputPaths = timedEntry.entry().getOutputPaths();
        if (outputPaths == null) {
          // An unknown secondary output could be anywhere. Until a normal cache hit backfills
          // this legacy entry, do not collect output bytes belonging to older/equal-time entries.
          if (newestUnknownOwner == null
              || timedEntry.lastAccessTime().isAfter(newestUnknownOwner)) {
            newestUnknownOwner = timedEntry.lastAccessTime();
          }
          continue;
        }
        var owner = new Ownership(item.getKey(), timedEntry.lastAccessTime());
        for (String path : outputPaths) {
          checkInterrupted();
          paths.computeIfAbsent(path, unused -> new ArrayList<>()).add(owner);
        }
      }
    }

    /**
     * A clean local action value can outlive a build made with the persistent action cache
     * disabled. Its outputs still have a current owner even if no entry was written for that owner.
     */
    void protectLocalOutputs(MemoizingEvaluator evaluator) throws InterruptedException {
      for (var value : evaluator.getDoneValues().values()) {
        checkInterrupted();
        if (!(value instanceof ActionExecutionValue action)) {
          continue;
        }
        for (var output : action.getAllFileValues().entrySet()) {
          checkInterrupted();
          if (!output.getValue().isRemote()) {
            protectLocalOutput(output.getKey().getExecPath());
          }
        }
        for (var output : action.getAllTreeArtifactValues().entrySet()) {
          checkInterrupted();
          if (!output.getValue().isEntirelyRemote()) {
            protectLocalOutput(output.getKey().getExecPath());
          }
        }
      }
    }

    private void protectLocalOutput(PathFragment path) {
      paths
          .computeIfAbsent(path.getPathString(), unused -> new ArrayList<>())
          .add(new Ownership("current-local-output", Instant.MAX));
    }

    boolean hasConflictingOwner(PathFragment path, String owner, Instant lastUse)
        throws InterruptedException {
      if (newestUnknownOwner != null && !newestUnknownOwner.isBefore(lastUse)) {
        return true;
      }
      for (PathFragment ancestor = path;
          ancestor != null && !ancestor.isEmpty();
          ancestor = ancestor.getParentDirectory()) {
        checkInterrupted();
        if (conflicts(paths.get(ancestor.getPathString()), owner, lastUse)) {
          return true;
        }
      }
      String prefix = path.getPathString() + "/";
      for (var descendant : paths.tailMap(prefix, true).entrySet()) {
        checkInterrupted();
        if (!descendant.getKey().startsWith(prefix)) {
          break;
        }
        if (conflicts(descendant.getValue(), owner, lastUse)) {
          return true;
        }
      }
      return false;
    }

    private static boolean conflicts(
        @Nullable List<Ownership> owners, String owner, Instant lastUse) {
      return owners != null
          && owners.stream()
              .anyMatch(
                  candidate ->
                      !candidate.key().equals(owner) && !candidate.lastUse().isBefore(lastUse));
    }
  }

  private record Ownership(String key, Instant lastUse) {}

  private record Group(
      PathFragment execPath, boolean tree, Map<PathFragment, FileArtifactValue> files) {
    long localSize(Set<PathFragment> localPaths) {
      return localPaths.stream().mapToLong(path -> files.get(path).getSize()).sum();
    }
  }

  private static boolean hasRemovedOutput(
      ActionExecutionValue value, Set<PathFragment> removedPaths) {
    return value.getAllFileValues().keySet().stream()
            .anyMatch(artifact -> removedPaths.contains(artifact.getExecPath()))
        || value.getAllTreeArtifactValues().keySet().stream()
            .anyMatch(artifact -> removedPaths.contains(artifact.getExecPath()));
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }
}
