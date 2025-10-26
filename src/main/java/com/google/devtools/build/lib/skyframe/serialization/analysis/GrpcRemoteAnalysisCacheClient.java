// Copyright 2025 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.serialization.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId.GitClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId.LongVersionClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId.SnapshotClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.CacheKey;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.ClientIdentifier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.FrontierVersion;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.RemoteAnalysisCacheGrpc;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/** gRPC backed implementation of {@link RemoteAnalysisCacheClient}. */
public final class GrpcRemoteAnalysisCacheClient implements RemoteAnalysisCacheClient {

  private final ManagedChannel channel;
  private final RemoteAnalysisCacheGrpc.RemoteAnalysisCacheFutureStub futureStub;
  private final long deadlineMillis;

  private final AtomicLong bytesSent = new AtomicLong();
  private final AtomicLong bytesReceived = new AtomicLong();
  private final AtomicLong requestsSent = new AtomicLong();
  private final AtomicLong batches = new AtomicLong();

  public GrpcRemoteAnalysisCacheClient(ManagedChannel channel, Duration deadline) {
    this.channel = channel;
    this.futureStub = RemoteAnalysisCacheGrpc.newFutureStub(channel);
    long deadlineMs = deadline == null ? 0L : deadline.toMillis();
    this.deadlineMillis = Math.max(1L, deadlineMs > 0 ? deadlineMs : 1L);
  }

  @Override
  public ListenableFuture<ByteString> lookup(ByteString fingerprint, FrontierNodeVersion version) {
    LookupRequest request =
        LookupRequest.newBuilder().setKey(buildCacheKey(fingerprint, version)).build();
    recordRequest(request.getSerializedSize());
    ListenableFuture<LookupResponse> responseFuture =
        futureStub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS).lookup(request);
    return Futures.transform(
        responseFuture,
        response -> {
          if (response == null || !response.getFound()) {
            return ByteString.EMPTY;
          }
          bytesReceived.addAndGet(response.getSerializedValue().size());
          return response.getSerializedValue();
        },
        directExecutor());
  }

  @Override
  public Stats getStats() {
    return new Stats(bytesSent.get(), bytesReceived.get(), requestsSent.get(), batches.get());
  }

  @Override
  public ListenableFuture<Boolean> addTopLevelTargets(
      String invocationId,
      long evaluatingVersion,
      String configurationHash,
      String bazelVersion,
      String area,
      Collection<String> targets,
      Collection<String> configFlags) {
    AddTopLevelTargetsRequest request =
        AddTopLevelTargetsRequest.newBuilder()
            .setInvocationId(invocationId)
            .setEvaluatingVersion(evaluatingVersion)
            .setConfigurationHash(Strings.nullToEmpty(configurationHash))
            .setBazelVersion(Strings.nullToEmpty(bazelVersion))
            .setArea(Strings.nullToEmpty(area))
            .addAllTargets(targets)
            .addAllConfigFlags(configFlags)
            .build();
    recordRequest(request.getSerializedSize());
    ListenableFuture<AddTopLevelTargetsResponse> responseFuture =
        futureStub
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .addTopLevelTargets(request);
    return Futures.transform(
        responseFuture, response -> response != null && response.getSuccess(), directExecutor());
  }

  @Override
  public void lookupTopLevelTargets(
      long evaluatingVersion,
      String configurationHash,
      String bazelVersion,
      String area,
      Collection<String> configFlags,
      EventHandler eventHandler) {
    LookupTopLevelTargetsRequest request =
        LookupTopLevelTargetsRequest.newBuilder()
            .setEvaluatingVersion(evaluatingVersion)
            .setConfigurationHash(Strings.nullToEmpty(configurationHash))
            .setBazelVersion(Strings.nullToEmpty(bazelVersion))
            .setArea(Strings.nullToEmpty(area))
            .addAllConfigFlags(configFlags)
            .build();
    recordRequest(request.getSerializedSize());
    ListenableFuture<LookupTopLevelTargetsResponse> responseFuture =
        futureStub
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .lookupTopLevelTargets(request);
    Futures.addCallback(
        responseFuture,
        new FutureCallback<LookupTopLevelTargetsResponse>() {
          @Override
          public void onSuccess(@Nullable LookupTopLevelTargetsResponse response) {
            if (response == null) {
              return;
            }
            if (!response.getTargetsList().isEmpty()) {
              eventHandler.handle(
                  Event.info(
                      String.format("Skycache metadata targets: %s", response.getTargetsList())));
            } else if (!Strings.isNullOrEmpty(response.getMessage())) {
              eventHandler.handle(Event.info(response.getMessage()));
            }
          }

          @Override
          public void onFailure(Throwable t) {
            eventHandler.handle(Event.warn("Skycache: Metadata lookup failed: " + t.getMessage()));
          }
        },
        directExecutor());
  }

  @Override
  public void shutdown() {
    channel.shutdownNow();
    try {
      channel.awaitTermination(SHUTDOWN_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private CacheKey buildCacheKey(ByteString fingerprint, FrontierNodeVersion version) {
    CacheKey.Builder builder = CacheKey.newBuilder().setFingerprint(fingerprint);
    if (version != null) {
      builder.setVersion(toProto(version));
    }
    return builder.build();
  }

  private FrontierVersion toProto(FrontierNodeVersion version) {
    FrontierVersion.Builder builder =
        FrontierVersion.newBuilder()
            .setTopLevelConfigChecksum(version.getTopLevelConfigChecksum())
            .setBlazeInstallMd5(ByteString.copyFrom(version.getBlazeInstallMD5().asBytes()))
            .setEvaluatingVersion(version.getEvaluatingVersion())
            .setUseFakeStampData(version.getUseFakeStampData())
            .setDistinguisher(version.getDistinguisher());
    version.getRevision().ifPresent(builder::setRevision);
    builder.setHasLocalChanges(version.hasLocalChanges());
    version.getClientId().ifPresent(clientId -> builder.setClientId(toProto(clientId)));
    return builder.build();
  }

  private static ClientIdentifier toProto(ClientId clientId) {
    ClientIdentifier.Builder builder = ClientIdentifier.newBuilder();
    if (clientId instanceof SnapshotClientId snapshot) {
      builder.setSnapshot(
          ClientIdentifier.Snapshot.newBuilder()
              .setWorkspaceId(snapshot.workspaceId())
              .setSnapshotVersion(snapshot.snapshotVersion()));
    } else if (clientId instanceof LongVersionClientId longVersion) {
      builder.setLongVersion(
          ClientIdentifier.LongVersion.newBuilder()
              .setEvaluatingVersion(longVersion.evaluatingVersion()));
    } else if (clientId instanceof GitClientId gitClient) {
      builder.setGitClient(
          ClientIdentifier.GitClient.newBuilder()
              .setRevision(gitClient.revision())
              .setHasLocalChanges(gitClient.hasLocalChanges()));
    }
    return builder.build();
  }

  private void recordRequest(int serializedSize) {
    bytesSent.addAndGet(serializedSize);
    requestsSent.incrementAndGet();
    batches.incrementAndGet();
  }
}
