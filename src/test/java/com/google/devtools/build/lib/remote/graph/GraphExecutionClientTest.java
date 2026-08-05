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

package com.google.devtools.build.lib.remote.graph;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.graph.v1.ActionNode;
import build.bazel.remote.execution.graph.v1.BeginAck;
import build.bazel.remote.execution.graph.v1.BeginGraph;
import build.bazel.remote.execution.graph.v1.CommitGraph;
import build.bazel.remote.execution.graph.v1.GraphExecuteRequest;
import build.bazel.remote.execution.graph.v1.GraphExecuteResponse;
import build.bazel.remote.execution.graph.v1.GraphExecutionGrpc;
import build.bazel.remote.execution.graph.v1.GraphExecutionGrpc.GraphExecutionImplBase;
import build.bazel.remote.execution.graph.v1.GraphProgress;
import build.bazel.remote.execution.graph.v1.GraphResult;
import build.bazel.remote.execution.graph.v1.GraphStreamError;
import build.bazel.remote.execution.graph.v1.MissingBlobs;
import build.bazel.remote.execution.graph.v1.NodeResult;
import build.bazel.remote.execution.graph.v1.UploadedBlobs;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.rpc.Code;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GraphExecutionClientTest {
  private final FakeGraphExecutionService service = new FakeGraphExecutionService();
  private ManagedChannel channel;
  private Server server;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
  }

  @After
  public void tearDown() throws Exception {
    channel.shutdownNow();
    server.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void sessionSequencesRequestsAndDispatchesResults() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    assertThat(service.requests(0)).hasSize(1);
    GraphExecuteRequest initialRequest = service.requests(0).get(0);
    assertThat(initialRequest.getSessionId()).isEqualTo("client-session");
    assertThat(initialRequest.getSequenceNumber()).isEqualTo(1);
    assertThat(initialRequest.getBegin().getProtocolVersion()).isEqualTo(1);
    assertThat(initialRequest.getBegin().getResumeToken()).hasSize(32);

    service.respond(0, response(1, 1).setBegin(beginAck().setProtocolVersion(1)).build());
    assertThat(session.beginFuture().get().getSessionId()).isEqualTo("client-session");

    List<GraphExecuteResponse.PayloadCase> observedPayloads = new ArrayList<>();
    session.setResponseListener(response -> observedPayloads.add(response.getPayloadCase()));
    long blobsSequence = session.sendUploadedBlobs(UploadedBlobs.getDefaultInstance());
    long actionSequence = session.sendAction(ActionNode.newBuilder().setNodeId("compile").build());
    long commitSequence =
        session.sendCommit(CommitGraph.newBuilder().setExpectedActionCount(1).build());
    ListenableFuture<Void> commitAcknowledged = session.acknowledgementFuture(commitSequence);
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("compile");

    assertThat(blobsSequence).isEqualTo(2);
    assertThat(actionSequence).isEqualTo(3);
    assertThat(commitSequence).isEqualTo(4);
    assertThat(service.requests(0).stream().map(GraphExecuteRequest::getSequenceNumber))
        .containsExactly(1L, 2L, 3L, 4L)
        .inOrder();

    service.respond(
        0,
        response(2, 3)
            .setProgress(GraphProgress.newBuilder().setDeclaredNodes(1).setRunningNodes(1))
            .build());
    service.respond(
        0, response(3, 3).setNodeResult(NodeResult.newBuilder().setNodeId("compile")).build());

    assertThat(nodeResult.get().getNodeId()).isEqualTo("compile");
    assertThat(commitAcknowledged.isDone()).isFalse();
    assertThat(session.acknowledgedRequestSequence()).isEqualTo(3);

    GraphResult graphResult = GraphResult.newBuilder().setCompletedNodes(1).build();
    service.respond(0, response(4, 4).setResult(graphResult).build());
    service.complete(0);

    assertThat(commitAcknowledged.get()).isNull();
    assertThat(session.resultFuture().get()).isEqualTo(graphResult);
    assertThat(observedPayloads)
        .containsExactly(
            GraphExecuteResponse.PayloadCase.PROGRESS,
            GraphExecuteResponse.PayloadCase.NODE_RESULT,
            GraphExecuteResponse.PayloadCase.RESULT)
        .inOrder();
  }

  @Test
  public void postBeginRequestRequiresBeginAckAndUsesAcknowledgedSessionId() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    assertThrows(
        IllegalStateException.class,
        () -> session.sendAction(ActionNode.newBuilder().setNodeId("compile").build()));
    assertThat(service.requests(0)).hasSize(1);

    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("compile").build());

    assertThat(service.requests(0).get(1).getSessionId()).isEqualTo("client-session");
    assertThat(service.requests(0).get(1).getSequenceNumber()).isEqualTo(2);
  }

  @Test
  public void retriableTransportFailureResumesWithConfiguredStub() throws Exception {
    AtomicInteger configuredStubCalls = new AtomicInteger();
    ClientInterceptor configuredInterceptor =
        new ClientInterceptor() {
          @Override
          public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
              MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions, Channel next) {
            configuredStubCalls.incrementAndGet();
            return next.newCall(method, callOptions);
          }
        };
    GraphExecutionClient.Session session =
        new GraphExecutionClient(
                GraphExecutionGrpc.newStub(channel).withInterceptors(configuredInterceptor))
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());

    session.sendUploadedBlobs(UploadedBlobs.getDefaultInstance());
    service.respond(0, response(2, 2).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    long commitSequence =
        session.sendCommit(CommitGraph.newBuilder().setExpectedActionCount(1).build());
    ListenableFuture<Void> commitAcknowledged = session.acknowledgementFuture(commitSequence);
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("one");
    session.close();
    service.fail(0, Status.UNAVAILABLE.asRuntimeException());

    assertThat(session.isConnected()).isTrue();
    assertThat(session.lastTransportError()).isNotNull();
    assertThat(configuredStubCalls.get()).isEqualTo(2);
    assertThat(service.streamCount()).isEqualTo(2);

    ImmutableList<GraphExecuteRequest> initialRequests = service.requests(0);
    ImmutableList<GraphExecuteRequest> resumedRequests = service.requests(1);
    assertThat(resumedRequests).hasSize(3);
    assertThat(resumedRequests.get(0).getSequenceNumber()).isEqualTo(0);
    assertThat(resumedRequests.get(0).getResume().getSessionId()).isEqualTo("client-session");
    assertThat(resumedRequests.get(0).getResume().getResumeToken())
        .isEqualTo(service.requests(0).get(0).getBegin().getResumeToken());
    assertThat(resumedRequests.get(0).getResume().getLastResponseSequence()).isEqualTo(2);
    assertThat(resumedRequests.get(1).toByteString())
        .isEqualTo(initialRequests.get(2).toByteString());
    assertThat(resumedRequests.get(2).toByteString())
        .isEqualTo(initialRequests.get(3).toByteString());
    assertThat(service.requestCompletions(1)).isEqualTo(1);

    // The server replays this response because the client only observed through sequence 2.
    service.respond(
        1, response(3, 4).setNodeResult(NodeResult.newBuilder().setNodeId("one")).build());
    service.respond(
        1, response(4, 4).setResult(GraphResult.newBuilder().setCompletedNodes(1)).build());
    service.complete(1);

    assertThat(nodeResult.get().getNodeId()).isEqualTo("one");
    assertThat(commitAcknowledged.get()).isNull();
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(1);
  }

  @Test
  public void immediateCallFailureCannotPublishStaleRequestObserver() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ClientInterceptor failFirstCallImmediately =
        new ClientInterceptor() {
          @Override
          public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
              MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions, Channel next) {
            if (calls.getAndIncrement() != 0) {
              return next.newCall(method, callOptions);
            }
            return new ClientCall<>() {
              @Override
              public void start(Listener<ResponseT> listener, Metadata headers) {
                listener.onClose(Status.UNAVAILABLE, new Metadata());
              }

              @Override
              public void request(int numMessages) {}

              @Override
              public void cancel(String message, Throwable cause) {}

              @Override
              public void halfClose() {}

              @Override
              public void sendMessage(RequestT message) {}
            };
          }
        };

    GraphExecutionClient.Session session =
        new GraphExecutionClient(
                GraphExecutionGrpc.newStub(channel).withInterceptors(failFirstCallImmediately))
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    assertThat(calls.get()).isEqualTo(2);
    assertThat(session.isConnected()).isTrue();
    assertThat(service.streamCount()).isEqualTo(1);
    ImmutableList<GraphExecuteRequest> requests = service.requests(0);
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).hasResume()).isTrue();
    assertThat(requests.get(1).hasBegin()).isTrue();
    assertThat(requests.get(1).getSequenceNumber()).isEqualTo(1);
    service.respond(
        0,
        response(1, 1)
            .setBegin(
                BeginAck.newBuilder()
                    .setSessionId(requests.get(1).getSessionId())
                    .setResumeToken(requests.get(1).getBegin().getResumeToken()))
            .build());
    service.respond(
        0, response(2, 1).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());

    assertThat(session.beginFuture().get().getSessionId()).isEqualTo("client-session");
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
  }

  @Test
  public void repeatedRetriableTransportFailuresResumeUntilSuccess() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    long actionSequence = session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    ListenableFuture<Void> actionAcknowledged = session.acknowledgementFuture(actionSequence);
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("one");

    service.fail(0, Status.UNAVAILABLE.asRuntimeException());
    service.fail(1, Status.UNAVAILABLE.asRuntimeException());
    service.fail(2, Status.UNAVAILABLE.asRuntimeException());
    service.fail(3, Status.UNAVAILABLE.asRuntimeException());

    assertThat(service.streamCount()).isEqualTo(5);
    assertThat(session.isConnected()).isTrue();
    for (int stream = 1; stream <= 4; stream++) {
      assertThat(service.requests(stream).get(0).hasResume()).isTrue();
      assertThat(service.requests(stream).get(1).toByteString())
          .isEqualTo(service.requests(0).get(1).toByteString());
    }

    service.respond(
        4, response(2, 2).setNodeResult(NodeResult.newBuilder().setNodeId("one")).build());
    service.respond(
        4, response(3, 2).setResult(GraphResult.newBuilder().setCompletedNodes(1)).build());

    assertThat(nodeResult.get().getNodeId()).isEqualTo("one");
    assertThat(actionAcknowledged.get()).isNull();
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(1);
  }

  @Test
  public void forwardResponseResetsReconnectBudgetButReplayDuplicateDoesNot() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    int failuresBeforeProgress = GraphExecutionClient.Session.MAX_RECONNECT_ATTEMPTS - 1;
    for (int stream = 0; stream < failuresBeforeProgress; stream++) {
      service.fail(stream, Status.UNAVAILABLE.asRuntimeException());
    }
    assertThat(session.reconnectAttempts()).isEqualTo(failuresBeforeProgress);

    int currentStream = failuresBeforeProgress;
    service.respond(currentStream, response(1, 1).setBegin(beginAck()).build());
    assertThat(session.reconnectAttempts()).isEqualTo(failuresBeforeProgress);

    service.respond(
        currentStream,
        response(2, 1).setProgress(GraphProgress.newBuilder().setDeclaredNodes(1)).build());
    assertThat(session.reconnectAttempts()).isEqualTo(0);

    for (int stream = currentStream; stream < currentStream + 3; stream++) {
      service.fail(stream, Status.UNAVAILABLE.asRuntimeException());
    }
    int recoveredStream = currentStream + 3;
    service.respond(
        recoveredStream,
        response(3, 2).setNodeResult(NodeResult.newBuilder().setNodeId("one")).build());
    service.respond(
        recoveredStream,
        response(4, 2).setResult(GraphResult.newBuilder().setCompletedNodes(1)).build());

    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(1);
  }

  @Test
  public void requestsSentDuringReconnectBackoffAreJournaledAndReplayedInOrder() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    service.fail(0, Status.UNAVAILABLE.asRuntimeException());

    CountDownLatch reconnectBackoffStarted = new CountDownLatch(1);
    session.setReconnectBackoffListener(reconnectBackoffStarted::countDown);
    Thread failingStream =
        new Thread(() -> service.fail(1, Status.UNAVAILABLE.asRuntimeException()));
    failingStream.start();
    assertThat(reconnectBackoffStarted.await(5, TimeUnit.SECONDS)).isTrue();

    long firstAction = session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    long secondAction = session.sendAction(ActionNode.newBuilder().setNodeId("two").build());
    long commit = session.sendCommit(CommitGraph.newBuilder().setExpectedActionCount(2).build());
    failingStream.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(failingStream.isAlive()).isFalse();
    assertThat(service.streamCount()).isEqualTo(3);
    ImmutableList<GraphExecuteRequest> replayed = service.requests(2);
    assertThat(replayed.stream().map(GraphExecuteRequest::getSequenceNumber))
        .containsExactly(0L, firstAction, secondAction, commit)
        .inOrder();
    assertThat(replayed.get(1).getAction().getNodeId()).isEqualTo("one");
    assertThat(replayed.get(2).getAction().getNodeId()).isEqualTo("two");
    assertThat(replayed.get(3).hasCommit()).isTrue();

    service.respond(
        2, response(2, commit).setNodeResult(NodeResult.newBuilder().setNodeId("one")).build());
    service.respond(
        2, response(3, commit).setNodeResult(NodeResult.newBuilder().setNodeId("two")).build());
    service.respond(
        2, response(4, commit).setResult(GraphResult.newBuilder().setCompletedNodes(2)).build());

    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(2);
  }

  @Test
  public void outboundRequestLimitBlocksUntilAcknowledgementFreesCapacity() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 2, 1024 * 1024)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    session.sendAction(ActionNode.newBuilder().setNodeId("two").build());

    CountDownLatch thirdSendStarted = new CountDownLatch(1);
    AtomicReference<Long> thirdSequence = new AtomicReference<>();
    AtomicReference<Throwable> thirdError = new AtomicReference<>();
    Thread thirdSender =
        new Thread(
            () -> {
              thirdSendStarted.countDown();
              try {
                thirdSequence.set(
                    session.sendAction(ActionNode.newBuilder().setNodeId("three").build()));
              } catch (Throwable t) {
                thirdError.set(t);
              }
            });
    thirdSender.start();
    assertThat(thirdSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    assertThat(thirdSender.isAlive()).isTrue();
    assertThat(service.requests(0)).hasSize(3);

    service.respond(
        0, response(2, 2).setProgress(GraphProgress.newBuilder().setDeclaredNodes(1)).build());
    thirdSender.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(thirdSender.isAlive()).isFalse();
    assertThat(thirdError.get()).isNull();
    assertThat(thirdSequence.get()).isEqualTo(4);
    assertThat(service.requests(0).stream().map(GraphExecuteRequest::getSequenceNumber))
        .containsExactly(1L, 2L, 3L, 4L)
        .inOrder();
    session.cancel("test complete", null);
  }

  @Test
  public void terminalAcknowledgementDoesNotLetBlockedSenderAppend() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 1, 1024 * 1024)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    CountDownLatch blockedSendStarted = new CountDownLatch(1);
    AtomicReference<Throwable> blockedError = new AtomicReference<>();
    Thread blockedSender =
        new Thread(
            () -> {
              blockedSendStarted.countDown();
              try {
                session.sendAction(ActionNode.newBuilder().setNodeId("two").build());
              } catch (Throwable t) {
                blockedError.set(t);
              }
            });
    blockedSender.start();
    assertThat(blockedSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    assertThat(blockedSender.isAlive()).isTrue();

    service.respond(
        0, response(2, 2).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());
    blockedSender.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(blockedSender.isAlive()).isFalse();
    assertThat(blockedError.get()).isInstanceOf(IllegalStateException.class);
    assertThat(service.requests(0)).hasSize(2);
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
  }

  @Test
  public void responseIdleWatchdogResumesSmallGraphWaitingForResult() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 100, 1024 * 1024, 50)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    long commit = session.sendCommit(CommitGraph.newBuilder().setExpectedActionCount(0).build());
    session.close();
    // An ack-only response is valid forward progress and resets the idle watchdog.
    service.respond(0, response(2, commit).build());

    waitForStreamCount(2);
    waitForRequestCount(1, 1);
    assertThat(service.requests(1)).hasSize(1);
    assertThat(service.requests(1).get(0).hasResume()).isTrue();
    assertThat(service.requests(1).get(0).getResume().getLastResponseSequence()).isEqualTo(2);
    service.respond(
        1, response(3, commit).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());

    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
    Thread.sleep(75);
    assertThat(service.streamCount()).isEqualTo(2);
  }

  @Test
  public void responseIdleWatchdogRecoversWhileSenderIsCapacityBlocked() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 1, 1024 * 1024, 50)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    CountDownLatch blockedSendStarted = new CountDownLatch(1);
    AtomicReference<Long> blockedSequence = new AtomicReference<>();
    AtomicReference<Throwable> blockedError = new AtomicReference<>();
    Thread blockedSender =
        new Thread(
            () -> {
              blockedSendStarted.countDown();
              try {
                blockedSequence.set(
                    session.sendAction(ActionNode.newBuilder().setNodeId("two").build()));
              } catch (Throwable t) {
                blockedError.set(t);
              }
            });
    blockedSender.start();
    assertThat(blockedSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    waitForStreamCount(2);
    waitForRequestCount(1, 2);
    assertThat(blockedSender.isAlive()).isTrue();
    assertThat(service.requests(1).stream().map(GraphExecuteRequest::getSequenceNumber))
        .containsExactly(0L, 2L)
        .inOrder();

    service.respond(1, response(2, 2).build());
    blockedSender.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(blockedSender.isAlive()).isFalse();
    assertThat(blockedError.get()).isNull();
    assertThat(blockedSequence.get()).isEqualTo(3);
    assertThat(service.requests(1).get(2).getAction().getNodeId()).isEqualTo("two");
    session.cancel("test complete", null);
  }

  @Test
  public void outboundByteLimitBlocksAndRejectsOversizedSingleRequest() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 100, 512)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("a".repeat(300)).build());

    CountDownLatch secondSendStarted = new CountDownLatch(1);
    AtomicReference<Throwable> secondError = new AtomicReference<>();
    Thread secondSender =
        new Thread(
            () -> {
              secondSendStarted.countDown();
              try {
                session.sendAction(ActionNode.newBuilder().setNodeId("b".repeat(300)).build());
              } catch (Throwable t) {
                secondError.set(t);
              }
            });
    secondSender.start();
    assertThat(secondSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    assertThat(secondSender.isAlive()).isTrue();

    service.respond(
        0, response(2, 2).setProgress(GraphProgress.newBuilder().setDeclaredNodes(1)).build());
    secondSender.join(TimeUnit.SECONDS.toMillis(5));
    assertThat(secondSender.isAlive()).isFalse();
    assertThat(secondError.get()).isNull();

    assertThrows(
        IllegalArgumentException.class,
        () -> session.sendAction(ActionNode.newBuilder().setNodeId("x".repeat(1_024)).build()));
    session.cancel("test complete", null);
  }

  @Test
  public void outboundCapacityWaitWakesOnCancellation() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 1, 1024 * 1024)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    CountDownLatch blockedSendStarted = new CountDownLatch(1);
    AtomicReference<Throwable> blockedError = new AtomicReference<>();
    Thread blockedSender =
        new Thread(
            () -> {
              blockedSendStarted.countDown();
              try {
                session.sendAction(ActionNode.newBuilder().setNodeId("two").build());
              } catch (Throwable t) {
                blockedError.set(t);
              }
            });
    blockedSender.start();
    assertThat(blockedSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    assertThat(blockedSender.isAlive()).isTrue();

    session.cancel("cancel blocked sender", null);
    blockedSender.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(blockedSender.isAlive()).isFalse();
    assertThat(blockedError.get()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void outboundCapacityWaitPropagatesInterruptedException() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(GraphExecutionGrpc.newStub(channel), 1, 1024 * 1024)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    CountDownLatch blockedSendStarted = new CountDownLatch(1);
    AtomicReference<Throwable> blockedError = new AtomicReference<>();
    Thread blockedSender =
        new Thread(
            () -> {
              blockedSendStarted.countDown();
              try {
                session.sendAction(ActionNode.newBuilder().setNodeId("two").build());
              } catch (Throwable t) {
                blockedError.set(t);
              }
            });
    blockedSender.start();
    assertThat(blockedSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(50);
    assertThat(blockedSender.isAlive()).isTrue();

    blockedSender.interrupt();
    blockedSender.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(blockedSender.isAlive()).isFalse();
    assertThat(blockedError.get()).isInstanceOf(InterruptedException.class);
    assertThat(service.requests(0)).hasSize(2);
    session.cancel("test complete", null);
  }

  @Test
  public void outboundCapacityWaitHonorsStubDeadline() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(
                GraphExecutionGrpc.newStub(channel).withDeadlineAfter(200, TimeUnit.MILLISECONDS),
                1,
                1024 * 1024)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> session.sendAction(ActionNode.newBuilder().setNodeId("two").build()));

    assertThat(error).hasMessageThat().contains("deadline");
    assertThat(session.isConnected()).isFalse();
  }

  @Test
  public void lostBeginAckResumesUsingClientGeneratedToken() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    GraphExecuteRequest initialBegin = service.requests(0).get(0);

    service.fail(0, Status.UNAVAILABLE.asRuntimeException());

    assertThat(service.streamCount()).isEqualTo(2);
    ImmutableList<GraphExecuteRequest> resumedRequests = service.requests(1);
    assertThat(resumedRequests).hasSize(2);
    assertThat(resumedRequests.get(0).getSequenceNumber()).isEqualTo(0);
    assertThat(resumedRequests.get(0).getResume().getSessionId()).isEqualTo("client-session");
    assertThat(resumedRequests.get(0).getResume().getResumeToken())
        .isEqualTo(initialBegin.getBegin().getResumeToken());
    assertThat(resumedRequests.get(0).getResume().getResumeToken()).hasSize(32);
    assertThat(resumedRequests.get(0).getResume().getLastResponseSequence()).isEqualTo(0);
    assertThat(resumedRequests.get(1).toByteString()).isEqualTo(initialBegin.toByteString());

    service.respond(1, response(1, 1).setBegin(beginAck()).build());
    service.respond(
        1, response(2, 1).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());

    assertThat(session.beginFuture().get().getSessionId()).isEqualTo("client-session");
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
  }

  @Test
  public void lostBeginAckRestartsBeginWhenResumeSessionWasNotFound() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    GraphExecuteRequest initialBegin = service.requests(0).get(0);

    service.fail(0, Status.UNAVAILABLE.asRuntimeException());
    service.fail(1, Status.NOT_FOUND.asRuntimeException());

    assertThat(service.streamCount()).isEqualTo(3);
    assertThat(service.requests(2)).containsExactly(initialBegin);
    service.respond(2, response(1, 1).setBegin(beginAck()).build());
    service.respond(
        2, response(2, 1).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());

    assertThat(session.beginFuture().get().getSessionId()).isEqualTo("client-session");
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
  }

  @Test
  public void nonRetriableTransportFailureDoesNotReconnect() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    service.fail(0, Status.PERMISSION_DENIED.asRuntimeException());

    assertThat(service.streamCount()).isEqualTo(1);
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(error).hasCauseThat().hasMessageThat().contains("failed before BeginAck");
  }

  @Test
  public void retriableTransportFailuresStopAtBoundedReconnectLimit() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());

    for (int stream = 0; stream <= GraphExecutionClient.Session.MAX_RECONNECT_ATTEMPTS; stream++) {
      service.fail(stream, Status.UNAVAILABLE.asRuntimeException());
    }

    assertThat(service.streamCount())
        .isEqualTo(GraphExecutionClient.Session.MAX_RECONNECT_ATTEMPTS + 1);
    assertThat(session.isConnected()).isFalse();
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(error).hasCauseThat().hasMessageThat().contains("after bounded reconnects");
  }

  @Test
  public void expiredStubDeadlineDoesNotReconnect() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(
                GraphExecutionGrpc.newStub(channel).withDeadlineAfter(50, TimeUnit.MILLISECONDS))
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> session.resultFuture().get(5, TimeUnit.SECONDS));

    assertThat(error).hasCauseThat().hasMessageThat().contains("deadline expired");
    assertThat(service.streamCount()).isEqualTo(1);
  }

  @Test
  public void cancellationPreventsFurtherReconnects() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    service.fail(0, Status.UNAVAILABLE.asRuntimeException());

    CountDownLatch reconnectBackoffStarted = new CountDownLatch(1);
    session.setReconnectBackoffListener(reconnectBackoffStarted::countDown);
    Thread failingStream =
        new Thread(() -> service.fail(1, Status.UNAVAILABLE.asRuntimeException()));
    failingStream.start();
    assertThat(reconnectBackoffStarted.await(5, TimeUnit.SECONDS)).isTrue();
    session.cancel("user interrupted recovery", null);
    failingStream.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(failingStream.isAlive()).isFalse();
    assertThat(service.streamCount()).isEqualTo(2);
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(error).hasCauseThat().hasMessageThat().contains("user interrupted recovery");
  }

  @Test
  public void resumeAdmissionConflictsCanOutlastCoordinatorLease() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("one");
    session.close();

    long startNanos = System.nanoTime();
    service.fail(0, Status.UNAVAILABLE.asRuntimeException());
    for (int stream = 1; stream <= 11; stream++) {
      service.fail(stream, Status.ALREADY_EXISTS.asRuntimeException());
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    assertThat(elapsedMillis).isAtLeast(2_000);
    assertThat(service.streamCount()).isEqualTo(13);
    assertThat(service.requests(12).get(0).getResume().getSessionId()).isEqualTo("client-session");
    assertThat(service.requests(12).get(1).toByteString())
        .isEqualTo(service.requests(0).get(1).toByteString());
    assertThat(service.requestCompletions(12)).isEqualTo(1);

    service.respond(
        12, response(2, 2).setNodeResult(NodeResult.newBuilder().setNodeId("one")).build());
    service.respond(
        12, response(3, 2).setResult(GraphResult.newBuilder().setCompletedNodes(1)).build());

    assertThat(nodeResult.get().getNodeId()).isEqualTo("one");
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(1);
  }

  @Test
  public void missingBlobsFailsSession() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    Digest missingDigest = Digest.newBuilder().setHash("missing").setSizeBytes(123).build();
    session.sendUploadedBlobs(UploadedBlobs.newBuilder().addDigests(missingDigest).build());
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("one");

    service.respond(
        0,
        response(2, 2)
            .setMissingBlobs(MissingBlobs.newBuilder().addDigests(missingDigest))
            .build());

    ExecutionException resultError =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(resultError)
        .hasCauseThat()
        .isInstanceOf(GraphExecutionClient.MissingBlobsException.class);
    GraphExecutionClient.MissingBlobsException cause =
        (GraphExecutionClient.MissingBlobsException) resultError.getCause();
    assertThat(cause.getDigests()).containsExactly(missingDigest);
    assertThrows(ExecutionException.class, () -> nodeResult.get());
    assertThat(service.requestErrors(0)).hasSize(1);
  }

  @Test
  public void terminalStreamErrorFailsOutstandingFutures() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    long actionSequence = session.sendAction(ActionNode.newBuilder().setNodeId("failed").build());
    ListenableFuture<Void> actionAcknowledged = session.acknowledgementFuture(actionSequence);
    ListenableFuture<NodeResult> nodeResult = session.nodeResultFuture("failed");

    service.respond(
        0,
        response(2, 1)
            .setError(
                GraphStreamError.newBuilder()
                    .setTerminal(true)
                    .setStatus(
                        com.google.rpc.Status.newBuilder()
                            .setCode(Code.INVALID_ARGUMENT_VALUE)
                            .setMessage("bad graph")))
            .build());

    ExecutionException resultError =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(resultError)
        .hasCauseThat()
        .isInstanceOf(GraphExecutionClient.GraphExecutionException.class);
    assertThat(resultError).hasCauseThat().hasMessageThat().contains("bad graph");
    assertThrows(ExecutionException.class, () -> actionAcknowledged.get());
    assertThrows(ExecutionException.class, () -> nodeResult.get());
    assertThat(service.requestErrors(0)).hasSize(1);
  }

  @Test
  public void beginAckWithoutResumeTokenFailsSession() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    service.respond(
        0, response(1, 1).setBegin(BeginAck.newBuilder().setSessionId("client-session")).build());

    ExecutionException error =
        assertThrows(ExecutionException.class, () -> session.beginFuture().get());
    assertThat(error).hasCauseThat().hasMessageThat().contains("empty resume token");
    assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(service.requestErrors(0)).hasSize(1);
  }

  @Test
  public void cancelFailsResultAndCancelsRequestStream() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());

    session.cancel("user interrupted", null);

    ExecutionException error =
        assertThrows(ExecutionException.class, () -> session.resultFuture().get());
    assertThat(error).hasCauseThat().hasMessageThat().contains("user interrupted");
    assertThat(service.requestErrors(0)).hasSize(1);
  }

  @Test
  public void closeHalfClosesRequestsAndStillAcceptsGraphResult() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    ListenableFuture<NodeResult> omittedNode = session.nodeResultFuture("omitted");

    session.close();

    assertThat(service.requestCompletions(0)).isEqualTo(1);
    service.respond(
        0, response(2, 1).setResult(GraphResult.newBuilder().setCompletedNodes(0)).build());
    service.complete(0);
    assertThat(session.resultFuture().get().getCompletedNodes()).isEqualTo(0);
    ExecutionException omittedNodeError =
        assertThrows(ExecutionException.class, () -> omittedNode.get());
    assertThat(omittedNodeError)
        .hasCauseThat()
        .hasMessageThat()
        .contains("without a NodeResult for omitted");
  }

  @Test
  public void graphResultTerminatesTransportWithoutWaitingForServerCompletion() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    ListenableFuture<NodeResult> omittedNode = session.nodeResultFuture("omitted");

    GraphResult result = GraphResult.newBuilder().setCompletedNodes(0).build();
    service.respond(0, response(2, 1).setResult(result).build());

    assertThat(session.resultFuture().get()).isEqualTo(result);
    assertThat(session.isConnected()).isFalse();
    assertThrows(ExecutionException.class, () -> omittedNode.get());
    assertThat(service.requestErrors(0)).hasSize(1);
  }

  @Test
  public void graphResultEstablishesTerminalStateBeforeReentrantPublication() throws Exception {
    GraphExecutionClient.Session session =
        new GraphExecutionClient(channel)
            .begin("client-session", BeginGraph.newBuilder().setProtocolVersion(1).build());
    service.respond(0, response(1, 1).setBegin(beginAck()).build());
    long actionSequence = session.sendAction(ActionNode.newBuilder().setNodeId("one").build());
    AtomicReference<Throwable> acknowledgementReentryError = new AtomicReference<>();
    session
        .acknowledgementFuture(actionSequence)
        .addListener(
            () -> {
              try {
                session.sendAction(ActionNode.newBuilder().setNodeId("ack-too-late").build());
              } catch (Throwable t) {
                acknowledgementReentryError.set(t);
              }
            },
            Runnable::run);
    AtomicReference<Boolean> resultDoneInsideListener = new AtomicReference<>();
    AtomicReference<Boolean> connectedInsideListener = new AtomicReference<>();
    AtomicReference<Throwable> reentrantSendError = new AtomicReference<>();
    session.setResponseListener(
        response -> {
          if (response.hasResult()) {
            resultDoneInsideListener.set(session.resultFuture().isDone());
            connectedInsideListener.set(session.isConnected());
            try {
              session.sendAction(ActionNode.newBuilder().setNodeId("too-late").build());
            } catch (Throwable t) {
              reentrantSendError.set(t);
            }
          }
        });

    GraphResult result = GraphResult.newBuilder().setCompletedNodes(0).build();
    service.respond(0, response(2, actionSequence).setResult(result).build());

    assertThat(acknowledgementReentryError.get()).isInstanceOf(IllegalStateException.class);
    assertThat(resultDoneInsideListener.get()).isFalse();
    assertThat(connectedInsideListener.get()).isFalse();
    assertThat(reentrantSendError.get()).isInstanceOf(IllegalStateException.class);
    assertThat(service.requests(0)).hasSize(2);
    assertThat(service.requestErrors(0)).hasSize(1);
    assertThat(session.resultFuture().get()).isEqualTo(result);
  }

  private static GraphExecuteResponse.Builder response(long sequence, long acknowledgement) {
    return GraphExecuteResponse.newBuilder()
        .setSequenceNumber(sequence)
        .setAckRequestSequence(acknowledgement);
  }

  private BeginAck.Builder beginAck() {
    GraphExecuteRequest beginRequest = service.requests(0).get(0);
    return BeginAck.newBuilder()
        .setSessionId(beginRequest.getSessionId())
        .setResumeToken(beginRequest.getBegin().getResumeToken());
  }

  private void waitForStreamCount(int expectedCount) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (service.streamCount() < expectedCount && System.nanoTime() < deadlineNanos) {
      Thread.sleep(5);
    }
    assertThat(service.streamCount()).isEqualTo(expectedCount);
  }

  private void waitForRequestCount(int stream, int expectedCount) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (service.requests(stream).size() < expectedCount && System.nanoTime() < deadlineNanos) {
      Thread.sleep(5);
    }
    assertThat(service.requests(stream)).hasSize(expectedCount);
  }

  private static final class FakeGraphExecutionService extends GraphExecutionImplBase {
    private final List<List<GraphExecuteRequest>> requestStreams = new ArrayList<>();
    private final List<List<Throwable>> requestErrors = new ArrayList<>();
    private final List<Integer> requestCompletions = new ArrayList<>();
    private final List<StreamObserver<GraphExecuteResponse>> responseObservers = new ArrayList<>();

    @Override
    public synchronized StreamObserver<GraphExecuteRequest> graphExecute(
        StreamObserver<GraphExecuteResponse> responseObserver) {
      int streamIndex = requestStreams.size();
      List<GraphExecuteRequest> requests = new ArrayList<>();
      List<Throwable> errors = new ArrayList<>();
      requestStreams.add(requests);
      requestErrors.add(errors);
      requestCompletions.add(0);
      responseObservers.add(responseObserver);
      return new StreamObserver<>() {
        @Override
        public void onNext(GraphExecuteRequest request) {
          synchronized (FakeGraphExecutionService.this) {
            requests.add(request);
          }
        }

        @Override
        public void onError(Throwable error) {
          synchronized (FakeGraphExecutionService.this) {
            errors.add(error);
          }
        }

        @Override
        public void onCompleted() {
          synchronized (FakeGraphExecutionService.this) {
            requestCompletions.set(streamIndex, 1);
          }
        }
      };
    }

    synchronized ImmutableList<GraphExecuteRequest> requests(int stream) {
      return ImmutableList.copyOf(requestStreams.get(stream));
    }

    synchronized ImmutableList<Throwable> requestErrors(int stream) {
      return ImmutableList.copyOf(requestErrors.get(stream));
    }

    synchronized int streamCount() {
      return requestStreams.size();
    }

    synchronized int requestCompletions(int stream) {
      return requestCompletions.get(stream);
    }

    synchronized void respond(int stream, GraphExecuteResponse response) {
      responseObservers.get(stream).onNext(response);
    }

    synchronized void fail(int stream, Throwable error) {
      responseObservers.get(stream).onError(error);
    }

    synchronized void complete(int stream) {
      responseObservers.get(stream).onCompleted();
    }
  }
}
