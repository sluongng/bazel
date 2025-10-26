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

package com.google.devtools.build.lib.skyframe.serialization.analysis.testing;

import com.google.common.base.Strings;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.RemoteAnalysisCacheGrpc;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Simple gRPC server used by shell tests to emulate a remote analysis cache. */
@SuppressWarnings("restriction") // sun.misc.Signal is used for simple test-only signal handling.
public final class MockRemoteAnalysisCacheServer {

  private final Server server;
  private final ConcurrentMap<ByteString, ByteString> valueStore = new ConcurrentHashMap<>();
  private final AtomicInteger lookupCount = new AtomicInteger();
  private final AtomicInteger metadataWriteCount = new AtomicInteger();
  private final AtomicInteger metadataLookupCount = new AtomicInteger();
  private final Path statsFile;
  private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

  private MockRemoteAnalysisCacheServer(int port, Path statsFile) {
    this.server = ServerBuilder.forPort(port).addService(new ServiceImpl()).build();
    this.statsFile = statsFile;
  }

  private void start() throws IOException {
    server.start();
    installSignalHandlers();
    System.out.printf("READY %d%n", server.getPort());
    System.out.flush();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  shutdown();
                  awaitTerminationUninterruptibly();
                }));
  }

  private void awaitTermination() throws InterruptedException {
    server.awaitTermination();
  }

  private void writeStats() {
    if (statsFile == null) {
      return;
    }
    String stats =
        String.format(
            "lookups=%d%nmetadata_writes=%d%nmetadata_lookups=%d%n",
            lookupCount.get(), metadataWriteCount.get(), metadataLookupCount.get());
    try {
      if (statsFile.getParent() != null) {
        Files.createDirectories(statsFile.getParent());
      }
      Files.writeString(statsFile, stats, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Best effort.
    }
  }

  private void shutdown() {
    if (!shutdownRequested.compareAndSet(false, true)) {
      return;
    }
    writeStats();
    server.shutdown();
  }

  private void installSignalHandlers() {
    installSignalHandler("TERM");
    installSignalHandler("INT");
  }

  private void installSignalHandler(String name) {
    try {
      Signal.handle(
          new Signal(name),
          new SignalHandler() {
            @Override
            public void handle(Signal signal) {
              shutdown();
            }
          });
    } catch (IllegalArgumentException unused) {
      // Signal not supported on this platform; ignore.
    }
  }

  private void awaitTerminationUninterruptibly() {
    boolean interrupted = false;
    while (true) {
      try {
        server.awaitTermination();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (!server.isTerminated()) {
      server.shutdownNow();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private final class ServiceImpl extends RemoteAnalysisCacheGrpc.RemoteAnalysisCacheImplBase {

    @Override
    public void lookup(LookupRequest request, StreamObserver<LookupResponse> responseObserver) {
      lookupCount.incrementAndGet();
      ByteString fingerprint = request.getKey().getFingerprint();
      ByteString value = valueStore.get(fingerprint);
      LookupResponse response =
          (value == null)
              ? LookupResponse.newBuilder().setFound(false).build()
              : LookupResponse.newBuilder().setFound(true).setSerializedValue(value).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void addTopLevelTargets(
        AddTopLevelTargetsRequest request,
        StreamObserver<AddTopLevelTargetsResponse> responseObserver) {
      metadataWriteCount.incrementAndGet();
      responseObserver.onNext(AddTopLevelTargetsResponse.newBuilder().setSuccess(true).build());
      responseObserver.onCompleted();
    }

    @Override
    public void lookupTopLevelTargets(
        LookupTopLevelTargetsRequest request,
        StreamObserver<LookupTopLevelTargetsResponse> responseObserver) {
      metadataLookupCount.incrementAndGet();
      responseObserver.onNext(
          LookupTopLevelTargetsResponse.newBuilder()
              .setHasEntries(false)
              .setMessage(
                  Strings.isNullOrEmpty(request.getConfigurationHash())
                      ? ""
                      : "No metadata recorded for configuration " + request.getConfigurationHash())
              .build());
      responseObserver.onCompleted();
    }
  }

  public static void main(String[] args) throws Exception {
    int port = -1;
    Path statsFile = null;
    for (String arg : args) {
      if (arg.startsWith("--port=")) {
        port = Integer.parseInt(arg.substring("--port=".length()));
      } else if (arg.startsWith("--stats_file=")) {
        statsFile = Paths.get(arg.substring("--stats_file=".length()));
      }
    }
    if (port <= 0) {
      throw new IllegalArgumentException("--port must be provided");
    }

    MockRemoteAnalysisCacheServer server = new MockRemoteAnalysisCacheServer(port, statsFile);
    server.start();
    server.awaitTermination();
  }
}
