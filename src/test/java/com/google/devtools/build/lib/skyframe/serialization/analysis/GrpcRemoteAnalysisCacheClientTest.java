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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.skyframe.serialization.FrontierNodeVersion;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.AddTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsRequest;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.LookupTopLevelTargetsResponse;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.RemoteAnalysisCacheGrpc;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GrpcRemoteAnalysisCacheClientTest {

  private FakeRemoteAnalysisCacheService service;
  private Server server;
  private ManagedChannel channel;
  private GrpcRemoteAnalysisCacheClient client;

  @Before
  public void setUp() throws Exception {
    service = new FakeRemoteAnalysisCacheService();
    String serverName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    client = new GrpcRemoteAnalysisCacheClient(channel, Duration.ofSeconds(5));
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.shutdown();
    }
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void lookupReturnsStoredValue() throws Exception {
    ByteString fingerprint = ByteString.copyFromUtf8("fingerprint");
    ByteString expectedValue = ByteString.copyFromUtf8("serialized-value");
    service.seedValue(fingerprint, expectedValue);

    ByteString result =
        client
            .lookup(fingerprint, FrontierNodeVersion.CONSTANT_FOR_TESTING)
            .get(5, TimeUnit.SECONDS);

    assertThat(result).isEqualTo(expectedValue);
    assertThat(service.getLookupCount()).isEqualTo(1);
  }

  @Test
  public void lookupMissingReturnsEmptyPayload() throws Exception {
    ByteString fingerprint = ByteString.copyFromUtf8("missing");

    ByteString result =
        client
            .lookup(fingerprint, FrontierNodeVersion.CONSTANT_FOR_TESTING)
            .get(5, TimeUnit.SECONDS);

    assertThat(result).isEqualTo(ByteString.EMPTY);
    assertThat(service.getLookupCount()).isEqualTo(1);
  }

  @Test
  public void metadataOperationsReachServer() throws Exception {
    boolean addResult =
        client
            .addTopLevelTargets(
                "invocation",
                /* evaluatingVersion= */ 42,
                /* configurationHash= */ "cfg",
                /* bazelVersion= */ "bazel-test",
                /* area= */ "analysis",
                ImmutableList.of("//foo:bar"),
                ImmutableList.of("--flag"))
            .get(5, TimeUnit.SECONDS);

    assertThat(addResult).isTrue();
    assertThat(service.awaitAddRequest(5, TimeUnit.SECONDS)).isTrue();
    AddTopLevelTargetsRequest addRequest = service.getLastAddRequest();
    assertThat(addRequest).isNotNull();
    assertThat(addRequest.getTargetsList()).containsExactly("//foo:bar");

    EventCollector events = new EventCollector(EventKind.INFO);
    client.lookupTopLevelTargets(
        /* evaluatingVersion= */ 42L,
        /* configurationHash= */ "cfg",
        /* bazelVersion= */ "bazel-test",
        /* area= */ "analysis",
        ImmutableList.of("--flag"),
        events);

    assertThat(service.awaitMetadataRequest(5, TimeUnit.SECONDS)).isTrue();
    LookupTopLevelTargetsRequest metadataRequest = service.getLastMetadataRequest();
    assertThat(metadataRequest).isNotNull();
    assertThat(metadataRequest.getConfigFlagsList()).containsExactly("--flag");

    assertThat(events.count()).isEqualTo(1);
    Event event = events.iterator().next();
    assertThat(event.getMessage()).contains("no entries for configuration cfg");
  }

  private static final class FakeRemoteAnalysisCacheService
      extends RemoteAnalysisCacheGrpc.RemoteAnalysisCacheImplBase {

    private final ConcurrentMap<ByteString, ByteString> valueStore = new ConcurrentHashMap<>();
    private final AtomicInteger lookupCount = new AtomicInteger();
    private final CountDownLatch addLatch = new CountDownLatch(1);
    private final CountDownLatch metadataLatch = new CountDownLatch(1);

    private volatile AddTopLevelTargetsRequest lastAddRequest;
    private volatile LookupTopLevelTargetsRequest lastMetadataRequest;

    void seedValue(ByteString fingerprint, ByteString value) {
      valueStore.put(fingerprint, value);
    }

    int getLookupCount() {
      return lookupCount.get();
    }

    AddTopLevelTargetsRequest getLastAddRequest() {
      return lastAddRequest;
    }

    LookupTopLevelTargetsRequest getLastMetadataRequest() {
      return lastMetadataRequest;
    }

    boolean awaitAddRequest(long timeout, TimeUnit unit) throws InterruptedException {
      return addLatch.await(timeout, unit);
    }

    boolean awaitMetadataRequest(long timeout, TimeUnit unit) throws InterruptedException {
      return metadataLatch.await(timeout, unit);
    }

    @Override
    public void lookup(LookupRequest request, StreamObserver<LookupResponse> responseObserver) {
      lookupCount.incrementAndGet();
      ByteString fingerprint = request.getKey().getFingerprint();
      ByteString value = valueStore.get(fingerprint);
      LookupResponse response;
      if (value == null) {
        response = LookupResponse.newBuilder().setFound(false).build();
      } else {
        response =
            LookupResponse.newBuilder()
                .setFound(true)
                .setSerializedValue(value)
                .build();
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void addTopLevelTargets(
        AddTopLevelTargetsRequest request,
        StreamObserver<AddTopLevelTargetsResponse> responseObserver) {
      lastAddRequest = request;
      responseObserver.onNext(AddTopLevelTargetsResponse.newBuilder().setSuccess(true).build());
      responseObserver.onCompleted();
      addLatch.countDown();
    }

    @Override
    public void lookupTopLevelTargets(
        LookupTopLevelTargetsRequest request,
        StreamObserver<LookupTopLevelTargetsResponse> responseObserver) {
      lastMetadataRequest = request;
      responseObserver.onNext(
          LookupTopLevelTargetsResponse.newBuilder()
              .setHasEntries(false)
              .setMessage("no entries for configuration " + request.getConfigurationHash())
              .build());
      responseObserver.onCompleted();
      metadataLatch.countDown();
    }
  }
}
