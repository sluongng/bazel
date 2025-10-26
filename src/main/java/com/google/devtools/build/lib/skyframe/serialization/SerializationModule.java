// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.serialization.analysis.GrpcRemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.errorprone.annotations.ForOverride;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A {@link BlazeModule} to store Skyframe serialization lifecycle hooks. */
public class SerializationModule extends BlazeModule {

  private RemoteAnalysisCachingServicesSupplier remoteAnalysisCachingServicesSupplier;

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    if (!directories.inWorkspace()) {
      // Serialization only works when the Bazel server is invoked from a workspace.
      // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
      // documentation HTML.
      return;
    }
    // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
    // expensive AutoRegistry.get call on clients that don't require it.
    builder.setAnalysisCodecRegistrySupplier(
        getAnalysisCodecRegistrySupplier(runtime, directories));

    remoteAnalysisCachingServicesSupplier = getAnalysisCachingServicesSupplier();
    builder.setRemoteAnalysisCachingServicesSupplier(remoteAnalysisCachingServicesSupplier);
  }

  @Override
  public void blazeShutdown() {
    shutdownAnalysisCacheClient();
  }

  @Override
  public void blazeShutdownOnCrash(DetailedExitCode exitCode) {
    shutdownAnalysisCacheClient();
  }

  private void shutdownAnalysisCacheClient() {
    @Nullable
    ListenableFuture<RemoteAnalysisCacheClient> analysisCacheClient =
        remoteAnalysisCachingServicesSupplier == null
            ? null
            : remoteAnalysisCachingServicesSupplier.getAnalysisCacheClient();
    if (analysisCacheClient != null) {
      analysisCacheClient.addListener(
          new Runnable() {
            @Override
            public void run() {
              try {
                analysisCacheClient
                    .get(RemoteAnalysisCacheClient.SHUTDOWN_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
                    .shutdown();
              } catch (ExecutionException | TimeoutException | InterruptedException e) {
                // There is no analysisCacheClient to shutdown.
                analysisCacheClient.cancel(/* mayInterruptIfRunning= */ false);
              }
            }
          },
          directExecutor());
    }
  }

  @ForOverride
  protected Supplier<ObjectCodecRegistry> getAnalysisCodecRegistrySupplier(
      BlazeRuntime runtime, BlazeDirectories directories) {
    return () ->
        SerializationRegistrySetupHelpers.initializeAnalysisCodecRegistryBuilder(
                runtime.getRuleClassProvider(),
                SerializationRegistrySetupHelpers.makeReferenceConstants(
                    directories,
                    runtime.getRuleClassProvider(),
                    directories.getWorkspace().getBaseName()))
            .build();
  }

  @ForOverride
  protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier() {
    return InMemoryRemoteAnalysisCachingServicesSupplier.INSTANCE;
  }

  /** A supplier that uses an in-memory fingerprint value service. */
  private static final class InMemoryRemoteAnalysisCachingServicesSupplier
      implements RemoteAnalysisCachingServicesSupplier {
    private static final InMemoryRemoteAnalysisCachingServicesSupplier INSTANCE =
        new InMemoryRemoteAnalysisCachingServicesSupplier();

    private static final FingerprintValueService SERVICE_INSTANCE =
        new FingerprintValueService(
            commonPool(),
            // TODO: b/358347099 - use a persistent store
            FingerprintValueStore.inMemoryStore(),
            new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
            FingerprintValueService.NONPROD_FINGERPRINTER,
            /* jsonLogWriter= */ null);

    private static final ListenableFuture<FingerprintValueService> WRAPPED_SERVICE_INSTANCE =
        immediateFuture(SERVICE_INSTANCE);

    @Nullable private ManagedChannel managedChannel;
    @Nullable private RemoteAnalysisCacheClient grpcClient;
    @Nullable private ListenableFuture<RemoteAnalysisCacheClient> grpcClientFuture;

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return WRAPPED_SERVICE_INSTANCE;
    }

    @Override
    public synchronized void configure(
        RemoteAnalysisCachingOptions cachingOptions,
        @Nullable com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId clientId,
        String buildId,
        @Nullable
            com.google.devtools.build.lib.skyframe.serialization.analysis
                    .RemoteAnalysisJsonLogWriter
                jsonLogWriter) {
      tearDownClient();
      if (cachingOptions == null || !cachingOptions.mode.requiresBackendConnectivity()) {
        return;
      }

      String target = chooseTarget(cachingOptions);
      if (Strings.isNullOrEmpty(target)) {
        return;
      }
      target = target.trim();
      if (target.isEmpty()) {
        return;
      }

      String normalizedTarget = normalizeGrpcTarget(target);
      boolean usePlaintext = shouldUsePlaintextTransport(target);
      ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(normalizedTarget);
      if (usePlaintext) {
        builder.usePlaintext();
      } else {
        builder.useTransportSecurity();
      }
      managedChannel = builder.build();
      grpcClient = new GrpcRemoteAnalysisCacheClient(managedChannel, cachingOptions.deadline);
      grpcClientFuture = immediateFuture(grpcClient);
    }

    @Override
    @Nullable
    public synchronized ListenableFuture<RemoteAnalysisCacheClient> getAnalysisCacheClient() {
      return grpcClientFuture;
    }

    private synchronized void tearDownClient() {
      if (grpcClient != null) {
        grpcClient.shutdown();
        grpcClient = null;
        grpcClientFuture = null;
      }
      if (managedChannel != null) {
        managedChannel.shutdownNow();
        managedChannel = null;
      }
    }

    private static String chooseTarget(RemoteAnalysisCachingOptions options) {
      if (!Strings.isNullOrEmpty(options.analysisCacheService)) {
        return options.analysisCacheService;
      }
      return options.remoteAnalysisCache;
    }

    private static boolean shouldUsePlaintextTransport(String target) {
      String lower = target.toLowerCase();
      return !(lower.startsWith("grpcs://") || lower.startsWith("https://"));
    }

    private static String normalizeGrpcTarget(String target) {
      String lower = target.toLowerCase();
      if (lower.startsWith("grpcs://")) {
        return target.substring("grpcs://".length());
      }
      if (lower.startsWith("grpc://")) {
        return target.substring("grpc://".length());
      }
      if (lower.startsWith("https://")) {
        return target.substring("https://".length());
      }
      if (lower.startsWith("http://")) {
        return target.substring("http://".length());
      }
      return target;
    }
  }
}
