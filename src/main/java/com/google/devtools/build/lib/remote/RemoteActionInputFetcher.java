// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.devtools.build.lib.remote.common.ProgressStatusListener.NO_ACTION;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput.EmptyActionInput;
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.SpawnProgressEvent;
import com.google.devtools.build.lib.remote.RemoteCache.DownloadProgressReporter;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.sandbox.SandboxHelpers;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.vfs.OutputPermissions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import io.reactivex.rxjava3.core.Completable;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Stages output files that are stored remotely to the local filesystem.
 *
 * <p>This is necessary when a locally executed action consumes outputs produced by a remotely
 * executed action and {@code --experimental_remote_download_outputs=minimal} is specified.
 */
class RemoteActionInputFetcher extends AbstractActionInputPrefetcher {

  private final String buildRequestId;
  private final String commandId;
  private final RemoteCache remoteCache;
  private final boolean useNewExitCodeForLostInputs;

  RemoteActionInputFetcher(
      Reporter reporter,
      String buildRequestId,
      String commandId,
      RemoteCache remoteCache,
      Path execRoot,
      TempPathGenerator tempPathGenerator,
      ImmutableList<Pattern> patternsToDownload,
      OutputPermissions outputPermissions,
      boolean useNewExitCodeForLostInputs) {
    super(reporter, execRoot, tempPathGenerator, patternsToDownload, outputPermissions);
    this.buildRequestId = Preconditions.checkNotNull(buildRequestId);
    this.commandId = Preconditions.checkNotNull(commandId);
    this.remoteCache = Preconditions.checkNotNull(remoteCache);
    this.useNewExitCodeForLostInputs = useNewExitCodeForLostInputs;
  }

  @Override
  public boolean supportsPartialTreeArtifactInputs() {
    // This prefetcher is unable to fetch only individual files inside a tree artifact.
    return false;
  }

  @Override
  protected void prefetchVirtualActionInput(VirtualActionInput input) throws IOException {
    if (!(input instanceof EmptyActionInput)) {
      Path outputPath = execRoot.getRelative(input.getExecPath());
      SandboxHelpers.atomicallyWriteVirtualInput(input, outputPath, ".remote");
    }
  }

  @Override
  protected boolean canDownloadFile(Path path, FileArtifactValue metadata) {
    return metadata.isRemote();
  }

  @Override
  protected ListenableFuture<Void> doDownloadFile(
      Reporter reporter,
      Path tempPath,
      PathFragment execPath,
      FileArtifactValue metadata,
      Priority priority)
      throws IOException {
    checkArgument(metadata.isRemote(), "Cannot download file that is not a remote file.");
    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, metadata.getActionId(), null);
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(requestMetadata);

    Digest digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());

    DownloadProgressReporter downloadProgressReporter;
    if (priority == Priority.LOW) {
      // Only report download progress for toplevel outputs
      downloadProgressReporter =
          new DownloadProgressReporter(
              /* includeFile= */ false,
              progress -> reporter.post(new DownloadProgress(progress)),
              execPath.toString(),
              metadata.getSize());
    } else {
      downloadProgressReporter = new DownloadProgressReporter(NO_ACTION, "", 0);
    }

    return remoteCache.downloadFile(context, tempPath, digest, downloadProgressReporter);
  }

  public static class DownloadProgress implements FetchProgress {
    private final SpawnProgressEvent progress;

    public DownloadProgress(SpawnProgressEvent progress) {
      this.progress = progress;
    }

    @Override
    public String getResourceIdentifier() {
      return progress.progressId();
    }

    @Override
    public String getProgress() {
      return progress.progress();
    }

    @Override
    public boolean isFinished() {
      return progress.finished();
    }
  }

  @Override
  protected Completable onErrorResumeNext(Throwable error) {
    if (error instanceof BulkTransferException) {
      if (((BulkTransferException) error).allCausedByCacheNotFoundException()) {
        var code =
            useNewExitCodeForLostInputs ? Code.REMOTE_CACHE_EVICTED : Code.REMOTE_CACHE_FAILED;
        error =
            new EnvironmentalExecException(
                (BulkTransferException) error,
                FailureDetail.newBuilder()
                    .setMessage("Failed to fetch blobs because they do not exist remotely.")
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(code))
                    .build());
      }
    }
    return Completable.error(error);
  }
}
