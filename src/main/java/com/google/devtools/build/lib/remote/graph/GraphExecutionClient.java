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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import build.bazel.remote.execution.graph.v1.ActionNode;
import build.bazel.remote.execution.graph.v1.BeginAck;
import build.bazel.remote.execution.graph.v1.BeginGraph;
import build.bazel.remote.execution.graph.v1.CommitGraph;
import build.bazel.remote.execution.graph.v1.GraphExecuteRequest;
import build.bazel.remote.execution.graph.v1.GraphExecuteResponse;
import build.bazel.remote.execution.graph.v1.GraphExecutionGrpc;
import build.bazel.remote.execution.graph.v1.GraphExecutionGrpc.GraphExecutionStub;
import build.bazel.remote.execution.graph.v1.GraphResult;
import build.bazel.remote.execution.graph.v1.GraphStreamError;
import build.bazel.remote.execution.graph.v1.MissingBlobs;
import build.bazel.remote.execution.graph.v1.NodeResult;
import build.bazel.remote.execution.graph.v1.ResumeGraph;
import build.bazel.remote.execution.graph.v1.UploadedBlobs;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Transport for the GraphExecution bidirectional streaming RPC.
 *
 * <p>This class does not own or close the channel supplied to it. Callers that use a pooled or
 * reference-counted channel remain responsible for keeping that channel alive for the lifetime of
 * every session.
 */
public final class GraphExecutionClient {
  // Bounds both the replay journal and the maximum amount handed to a live gRPC stream without
  // server acknowledgement.
  private static final int DEFAULT_MAX_UNACKNOWLEDGED_REQUESTS = 1_024;
  private static final long DEFAULT_MAX_UNACKNOWLEDGED_BYTES = 16L * 1024 * 1024;
  private static final long DEFAULT_RESPONSE_IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
  private static final ScheduledExecutorService WATCHDOG_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "graph-execution-response-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final GraphExecutionStub stub;
  private final int maxUnacknowledgedRequests;
  private final long maxUnacknowledgedBytes;
  private final long responseIdleTimeoutMillis;

  /** Creates a client on a caller-owned channel. */
  public GraphExecutionClient(Channel channel) {
    this(
        GraphExecutionGrpc.newStub(channel),
        DEFAULT_MAX_UNACKNOWLEDGED_REQUESTS,
        DEFAULT_MAX_UNACKNOWLEDGED_BYTES,
        DEFAULT_RESPONSE_IDLE_TIMEOUT_MILLIS);
  }

  /**
   * Creates a client with a preconfigured stub.
   *
   * <p>This constructor is useful when the caller needs to install call credentials, interceptors,
   * deadlines, or request metadata on the stub.
   */
  public GraphExecutionClient(GraphExecutionStub stub) {
    this(
        stub,
        DEFAULT_MAX_UNACKNOWLEDGED_REQUESTS,
        DEFAULT_MAX_UNACKNOWLEDGED_BYTES,
        DEFAULT_RESPONSE_IDLE_TIMEOUT_MILLIS);
  }

  @VisibleForTesting
  GraphExecutionClient(
      GraphExecutionStub stub, int maxUnacknowledgedRequests, long maxUnacknowledgedBytes) {
    this(
        stub,
        maxUnacknowledgedRequests,
        maxUnacknowledgedBytes,
        DEFAULT_RESPONSE_IDLE_TIMEOUT_MILLIS);
  }

  @VisibleForTesting
  GraphExecutionClient(
      GraphExecutionStub stub,
      int maxUnacknowledgedRequests,
      long maxUnacknowledgedBytes,
      long responseIdleTimeoutMillis) {
    checkArgument(maxUnacknowledgedRequests > 0, "maxUnacknowledgedRequests must be positive");
    checkArgument(maxUnacknowledgedBytes > 0, "maxUnacknowledgedBytes must be positive");
    checkArgument(responseIdleTimeoutMillis > 0, "responseIdleTimeoutMillis must be positive");
    this.stub = stub;
    this.maxUnacknowledgedRequests = maxUnacknowledgedRequests;
    this.maxUnacknowledgedBytes = maxUnacknowledgedBytes;
    this.responseIdleTimeoutMillis = responseIdleTimeoutMillis;
  }

  /** Opens a stream and immediately sends the sequence-one {@link BeginGraph} request. */
  public Session begin(String sessionId, BeginGraph begin) {
    checkArgument(!sessionId.isEmpty(), "sessionId must not be empty");
    return new Session(
        stub,
        sessionId,
        begin,
        maxUnacknowledgedRequests,
        maxUnacknowledgedBytes,
        responseIdleTimeoutMillis);
  }

  /**
   * A logical GraphExecute session.
   *
   * <p>Retriable transport failures are resumed using the same configured stub, subject to a
   * bounded retry count and the stub's deadline. Because the resume token is generated before
   * {@link BeginGraph} is sent, a session can also be resumed when its {@link BeginAck} is lost.
   */
  public static final class Session implements AutoCloseable {
    private static final int RESUME_TOKEN_SIZE = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    @VisibleForTesting static final int MAX_RECONNECT_ATTEMPTS = 16;
    private static final long MAX_RECONNECT_BACKOFF_MILLIS = 320;

    private final Object lock = new Object();
    private final SettableFuture<BeginAck> beginFuture = SettableFuture.create();
    private final SettableFuture<GraphResult> resultFuture = SettableFuture.create();
    private final Map<String, SettableFuture<NodeResult>> nodeResultFutures = new HashMap<>();
    private final Map<String, NodeResult> completedNodeResults = new HashMap<>();
    private final NavigableMap<Long, GraphExecuteRequest> unacknowledgedRequests = new TreeMap<>();
    private final NavigableMap<Long, List<SettableFuture<Void>>> acknowledgementFutures =
        new TreeMap<>();

    private final GraphExecutionStub stub;
    private final int maxUnacknowledgedRequests;
    private final long maxUnacknowledgedBytes;
    private final long responseIdleTimeoutMillis;
    private final String sessionId;
    private final byte[] resumeToken;
    private final GraphExecuteRequest beginRequest;
    private long nextRequestSequence = 1;
    private long highestSentRequestSequence;
    private long acknowledgedRequestSequence;
    private long unacknowledgedRequestBytes;
    private long lastResponseSequence;
    private long streamGeneration;
    private boolean connected;
    private boolean requestsClosed;
    private boolean currentStreamIsResume;
    private int reconnectAttempts;
    private boolean terminal;
    @Nullable private ClientCallStreamObserver<GraphExecuteRequest> requestObserver;
    private Consumer<GraphExecuteResponse> responseListener = unused -> {};
    private Runnable reconnectBackoffListener = () -> {};
    private long watchdogEpoch;
    @Nullable private ScheduledFuture<?> watchdogFuture;
    @Nullable private Throwable lastTransportError;
    @Nullable private Throwable terminalError;

    private Session(
        GraphExecutionStub stub,
        String sessionId,
        BeginGraph begin,
        int maxUnacknowledgedRequests,
        long maxUnacknowledgedBytes,
        long responseIdleTimeoutMillis) {
      this.stub = stub;
      this.maxUnacknowledgedRequests = maxUnacknowledgedRequests;
      this.maxUnacknowledgedBytes = maxUnacknowledgedBytes;
      this.responseIdleTimeoutMillis = responseIdleTimeoutMillis;
      this.sessionId = sessionId;
      this.resumeToken = new byte[RESUME_TOKEN_SIZE];
      SECURE_RANDOM.nextBytes(resumeToken);
      BeginGraph durableBegin =
          begin.toBuilder().setResumeToken(ByteString.copyFrom(resumeToken)).build();
      synchronized (lock) {
        beginRequest =
            sequenceRequestLocked(GraphExecuteRequest.newBuilder().setBegin(durableBegin));
        checkArgument(
            beginRequest.getSerializedSize() <= maxUnacknowledgedBytes,
            "BeginGraph request size %s exceeds the outbound limit %s",
            beginRequest.getSerializedSize(),
            maxUnacknowledgedBytes);
        journalRequestLocked(beginRequest);
        long generation = openStreamLocked(/* isResume= */ false);
        if (isCurrentConnectedStreamLocked(generation)) {
          sendOnCurrentStreamLocked(generation, beginRequest);
        }
        if (watchdogFuture == null && !terminal) {
          scheduleWatchdogLocked(/* madeProgress= */ false);
        }
      }
    }

    /** Installs a listener invoked serially for each non-duplicate response. */
    public void setResponseListener(Consumer<GraphExecuteResponse> listener) {
      synchronized (lock) {
        responseListener = listener;
      }
    }

    public ListenableFuture<BeginAck> beginFuture() {
      return beginFuture;
    }

    public ListenableFuture<GraphResult> resultFuture() {
      return resultFuture;
    }

    /**
     * Returns the result for {@code nodeId}.
     *
     * <p>The future works whether it is requested before or after the response arrives.
     */
    public ListenableFuture<NodeResult> nodeResultFuture(String nodeId) {
      checkArgument(!nodeId.isEmpty(), "nodeId must not be empty");
      synchronized (lock) {
        NodeResult completed = completedNodeResults.get(nodeId);
        if (completed != null) {
          SettableFuture<NodeResult> future = SettableFuture.create();
          future.set(completed);
          return future;
        }
        if (terminal) {
          Throwable error =
              terminalError != null
                  ? terminalError
                  : new IOException("GraphExecute completed without a NodeResult for " + nodeId);
          return immediateFailedFuture(error);
        }
        return nodeResultFutures.computeIfAbsent(nodeId, unused -> SettableFuture.create());
      }
    }

    @CanIgnoreReturnValue
    public long sendUploadedBlobs(UploadedBlobs uploadedBlobs) throws InterruptedException {
      synchronized (lock) {
        return sendLocked(GraphExecuteRequest.newBuilder().setUploadedBlobs(uploadedBlobs));
      }
    }

    @CanIgnoreReturnValue
    public long sendAction(ActionNode action) throws InterruptedException {
      checkArgument(!action.getNodeId().isEmpty(), "action nodeId must not be empty");
      synchronized (lock) {
        return sendLocked(GraphExecuteRequest.newBuilder().setAction(action));
      }
    }

    @CanIgnoreReturnValue
    public long sendCommit(CommitGraph commit) throws InterruptedException {
      synchronized (lock) {
        return sendLocked(GraphExecuteRequest.newBuilder().setCommit(commit));
      }
    }

    /** Completes when the server has incorporated every request through {@code requestSequence}. */
    public ListenableFuture<Void> acknowledgementFuture(long requestSequence) {
      synchronized (lock) {
        checkArgument(
            requestSequence > 0 && requestSequence <= highestSentRequestSequence,
            "unknown request sequence %s",
            requestSequence);
        if (requestSequence <= acknowledgedRequestSequence) {
          return immediateVoidFuture();
        }
        if (terminal) {
          return immediateFailedFuture(
              terminalError != null
                  ? terminalError
                  : new IOException(
                      "GraphExecute completed before acknowledging request " + requestSequence));
        }
        SettableFuture<Void> future = SettableFuture.create();
        acknowledgementFutures
            .computeIfAbsent(requestSequence, unused -> new ArrayList<>())
            .add(future);
        return future;
      }
    }

    public boolean isConnected() {
      synchronized (lock) {
        return connected;
      }
    }

    @Nullable
    public Throwable lastTransportError() {
      synchronized (lock) {
        return lastTransportError;
      }
    }

    public String sessionId() {
      synchronized (lock) {
        return sessionId;
      }
    }

    public long acknowledgedRequestSequence() {
      synchronized (lock) {
        return acknowledgedRequestSequence;
      }
    }

    public long lastResponseSequence() {
      synchronized (lock) {
        return lastResponseSequence;
      }
    }

    @VisibleForTesting
    ImmutableList<GraphExecuteRequest> unacknowledgedRequests() {
      synchronized (lock) {
        return ImmutableList.copyOf(unacknowledgedRequests.values());
      }
    }

    @VisibleForTesting
    void setReconnectBackoffListener(Runnable listener) {
      synchronized (lock) {
        reconnectBackoffListener = listener;
      }
    }

    @VisibleForTesting
    int reconnectAttempts() {
      synchronized (lock) {
        return reconnectAttempts;
      }
    }

    /** Half-closes the request stream. Responses continue until the server completes the RPC. */
    @Override
    public void close() {
      synchronized (lock) {
        if (requestsClosed || terminal) {
          return;
        }
        requestsClosed = true;
        lock.notifyAll();
        if (connected && requestObserver != null) {
          requestObserver.onCompleted();
        }
      }
    }

    /** Cancels the RPC and fails every result and acknowledgement future. */
    public void cancel(String message, @Nullable Throwable cause) {
      synchronized (lock) {
        if (terminal) {
          return;
        }
        IOException cancellation =
            new IOException(message, cause != null ? cause : Status.CANCELLED.asException());
        failLocked(cancellation);
      }
    }

    private void cancelTransportLocked(String message, Throwable cause) {
      if (requestObserver != null) {
        requestObserver.cancel(message, cause);
      }
    }

    private long sendLocked(GraphExecuteRequest.Builder request) throws InterruptedException {
      checkState(!terminal, "session is terminal");
      checkState(!requestsClosed, "session request stream is closed");
      checkState(
          request.getPayloadCase() == GraphExecuteRequest.PayloadCase.BEGIN || beginFuture.isDone(),
          "GraphExecute BeginAck must be received before post-begin requests");
      GraphExecuteRequest candidate =
          request.setSessionId(sessionId).setSequenceNumber(nextRequestSequence).build();
      awaitOutboundCapacityLocked(candidate.getSerializedSize());
      GraphExecuteRequest sequenced = sequenceRequestLocked(request);
      journalRequestLocked(sequenced);
      if (connected && requestObserver != null) {
        sendOnCurrentStreamLocked(streamGeneration, sequenced);
      }
      return sequenced.getSequenceNumber();
    }

    private GraphExecuteRequest sequenceRequestLocked(GraphExecuteRequest.Builder request) {
      long sequence = nextRequestSequence++;
      highestSentRequestSequence = sequence;
      return request.setSessionId(sessionId).setSequenceNumber(sequence).build();
    }

    private void journalRequestLocked(GraphExecuteRequest request) {
      unacknowledgedRequests.put(request.getSequenceNumber(), request);
      unacknowledgedRequestBytes += request.getSerializedSize();
    }

    private void awaitOutboundCapacityLocked(int requestSize) throws InterruptedException {
      checkArgument(
          requestSize <= maxUnacknowledgedBytes,
          "GraphExecute request size %s exceeds the outbound limit %s",
          requestSize,
          maxUnacknowledgedBytes);
      while (unacknowledgedRequests.size() >= maxUnacknowledgedRequests
          || unacknowledgedRequestBytes + requestSize > maxUnacknowledgedBytes) {
        checkState(!terminal, "session is terminal: %s", terminalError);
        checkState(!requestsClosed, "session request stream is closed");
        Deadline deadline = stub.getCallOptions().getDeadline();
        long waitMillis = 0;
        if (deadline != null) {
          waitMillis = deadline.timeRemaining(TimeUnit.MILLISECONDS);
          if (waitMillis <= 0) {
            IOException error =
                new IOException("GraphExecute deadline expired awaiting outbound capacity");
            failLocked(error);
            throw new IllegalStateException(error);
          }
        }
        try {
          lock.wait(waitMillis);
        } catch (InterruptedException e) {
          throw e;
        }
      }
      // An acknowledgement and a terminal result may arrive in the same response. The
      // acknowledgement wakes this sender and frees capacity before result processing marks the
      // session terminal, so state must be checked again after leaving the capacity loop.
      checkState(!terminal, "session is terminal: %s", terminalError);
      checkState(!requestsClosed, "session request stream is closed");
    }

    private long openStreamLocked(boolean isResume) {
      long generation = ++streamGeneration;
      connected = false;
      requestObserver = null;
      currentStreamIsResume = isResume;
      stub.graphExecute(new ResponseObserver(generation, isResume));
      return generation;
    }

    private boolean isCurrentConnectedStreamLocked(long generation) {
      return generation == streamGeneration && connected && requestObserver != null && !terminal;
    }

    private boolean sendOnCurrentStreamLocked(
        long generation, GraphExecuteRequest graphExecuteRequest) {
      if (!isCurrentConnectedStreamLocked(generation)) {
        return false;
      }
      requestObserver.onNext(graphExecuteRequest);
      return isCurrentConnectedStreamLocked(generation);
    }

    private void onResponse(long generation, GraphExecuteResponse response) {
      synchronized (lock) {
        if (generation != streamGeneration || terminal) {
          return;
        }
        long responseSequence = response.getSequenceNumber();
        if (responseSequence <= lastResponseSequence) {
          return;
        }
        if (responseSequence != lastResponseSequence + 1) {
          failLocked(
              new IOException(
                  String.format(
                      "non-contiguous GraphExecute response sequence: got %d after %d",
                      responseSequence, lastResponseSequence)));
          return;
        }
        if (response.getAckRequestSequence() < acknowledgedRequestSequence
            || response.getAckRequestSequence() > highestSentRequestSequence) {
          failLocked(
              new IOException(
                  String.format(
                      "invalid GraphExecute acknowledgement %d (previous %d, highest sent %d)",
                      response.getAckRequestSequence(),
                      acknowledgedRequestSequence,
                      highestSentRequestSequence)));
          return;
        }

        boolean resultResponse =
            response.getPayloadCase() == GraphExecuteResponse.PayloadCase.RESULT;
        if (resultResponse && response.getAckRequestSequence() < highestSentRequestSequence) {
          failLocked(
              new IOException(
                  "GraphExecute returned a GraphResult with unacknowledged requests through "
                      + highestSentRequestSequence));
          return;
        }

        // A newly accepted protocol response proves that the current coordinator made forward
        // progress. Replay duplicates return above and deliberately do not replenish the budget.
        reconnectAttempts = 0;
        scheduleWatchdogLocked(/* madeProgress= */ true);
        lastResponseSequence = responseSequence;
        if (resultResponse) {
          establishResultTerminalLocked();
        }
        advanceAcknowledgementLocked(response.getAckRequestSequence());

        switch (response.getPayloadCase()) {
          case BEGIN:
            handleBeginLocked(response.getBegin());
            break;
          case NODE_RESULT:
            handleNodeResultLocked(response.getNodeResult());
            break;
          case RESULT:
            finalizeResultLocked();
            break;
          case ERROR:
            handleStreamErrorLocked(response.getError());
            break;
          case MISSING_BLOBS:
            MissingBlobsException missingBlobsError =
                new MissingBlobsException(response.getMissingBlobs());
            failLocked(missingBlobsError);
            break;
          case PROGRESS:
          case PAYLOAD_NOT_SET:
            break;
        }
        if (!terminal || resultResponse) {
          try {
            responseListener.accept(response);
          } catch (RuntimeException e) {
            IOException listenerError = new IOException("GraphExecute response listener failed", e);
            if (resultResponse) {
              terminalError = listenerError;
              resultFuture.setException(listenerError);
              return;
            }
            failLocked(listenerError);
          }
        }
        if (resultResponse) {
          resultFuture.set(response.getResult());
        }
      }
    }

    private void establishResultTerminalLocked() {
      terminal = true;
      cancelWatchdogLocked();
      lock.notifyAll();
      if (connected) {
        IOException cancellation =
            new IOException("GraphExecute logical result received", Status.CANCELLED.asException());
        cancelTransportLocked(cancellation.getMessage(), cancellation);
      }
      connected = false;
      requestObserver = null;
    }

    private void finalizeResultLocked() {
      for (Map.Entry<String, SettableFuture<NodeResult>> entry : nodeResultFutures.entrySet()) {
        if (!entry.getValue().isDone()) {
          entry
              .getValue()
              .setException(
                  new IOException(
                      "GraphExecute completed without a NodeResult for " + entry.getKey()));
        }
      }
    }

    private void handleBeginLocked(BeginAck begin) {
      if (begin.getSessionId().isEmpty()) {
        failLocked(new IOException("GraphExecute BeginAck has an empty session ID"));
        return;
      }
      if (begin.getResumeToken().isEmpty()) {
        failLocked(new IOException("GraphExecute BeginAck has an empty resume token"));
        return;
      }
      if (!sessionId.equals(begin.getSessionId())) {
        failLocked(
            new IOException(
                String.format(
                    "GraphExecute BeginAck changed session ID from %s to %s",
                    sessionId, begin.getSessionId())));
        return;
      }
      if (!Arrays.equals(resumeToken, begin.getResumeToken().toByteArray())) {
        failLocked(new IOException("GraphExecute BeginAck did not echo the client resume token"));
        return;
      }
      if (beginFuture.isDone()) {
        return;
      }
      beginFuture.set(begin);
    }

    private void handleNodeResultLocked(NodeResult nodeResult) {
      if (nodeResult.getNodeId().isEmpty()) {
        failLocked(new IOException("GraphExecute NodeResult has an empty node ID"));
        return;
      }
      NodeResult previous = completedNodeResults.putIfAbsent(nodeResult.getNodeId(), nodeResult);
      if (previous != null && !previous.equals(nodeResult)) {
        failLocked(
            new IOException(
                "conflicting duplicate GraphExecute NodeResult for " + nodeResult.getNodeId()));
        return;
      }
      SettableFuture<NodeResult> future = nodeResultFutures.get(nodeResult.getNodeId());
      if (future != null) {
        future.set(nodeResult);
      }
    }

    private void handleStreamErrorLocked(GraphStreamError error) {
      if (!error.getTerminal()) {
        return;
      }
      failLocked(new GraphExecutionException(error));
    }

    private void advanceAcknowledgementLocked(long acknowledgement) {
      if (acknowledgement == acknowledgedRequestSequence) {
        return;
      }
      acknowledgedRequestSequence = acknowledgement;
      for (GraphExecuteRequest request :
          unacknowledgedRequests.headMap(acknowledgement, true).values()) {
        unacknowledgedRequestBytes -= request.getSerializedSize();
      }
      unacknowledgedRequests.headMap(acknowledgement, true).clear();
      checkState(unacknowledgedRequestBytes >= 0, "negative unacknowledged request byte count");
      lock.notifyAll();
      NavigableMap<Long, List<SettableFuture<Void>>> completed =
          new TreeMap<>(acknowledgementFutures.headMap(acknowledgement, true));
      acknowledgementFutures.headMap(acknowledgement, true).clear();
      for (List<SettableFuture<Void>> futures : completed.values()) {
        for (SettableFuture<Void> future : futures) {
          future.set(null);
        }
      }
    }

    private void onTransportError(long generation, Throwable error) {
      synchronized (lock) {
        if (generation != streamGeneration || terminal) {
          return;
        }
        lastTransportError = error;
        connected = false;
        requestObserver = null;
        if (recoverTransportLocked(error)) {
          return;
        }
        String reason =
            deadlineExpired()
                ? "GraphExecute deadline expired during recovery"
                : reconnectAttempts >= MAX_RECONNECT_ATTEMPTS
                    ? "GraphExecute transport failed after bounded reconnects"
                    : !beginFuture.isDone()
                        ? "GraphExecute stream failed before BeginAck"
                        : "GraphExecute transport failed";
        failLocked(new IOException(reason, error));
      }
    }

    private boolean recoverTransportLocked(Throwable error) {
      Status.Code code = Status.fromThrowable(error).getCode();
      boolean restartLostBegin =
          !beginFuture.isDone() && currentStreamIsResume && code == Status.Code.NOT_FOUND;
      if ((!restartLostBegin && !isRetriableTransportError(error))
          || deadlineExpired()
          || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        return false;
      }

      reconnectAttempts++;
      long backoffMillis =
          Math.min(
              MAX_RECONNECT_BACKOFF_MILLIS,
              reconnectAttempts == 1 ? 0 : 10L << Math.min(reconnectAttempts - 2, 5));
      if (!waitForRetryLocked(backoffMillis, error)) {
        return true;
      }

      try {
        if (restartLostBegin) {
          restartBeginLocked();
        } else {
          resumeLocked();
        }
        return true;
      } catch (RuntimeException reconnectError) {
        error.addSuppressed(reconnectError);
        return false;
      }
    }

    private boolean waitForRetryLocked(long requestedBackoffMillis, Throwable error) {
      long backoffMillis = requestedBackoffMillis;
      Deadline deadline = stub.getCallOptions().getDeadline();
      if (deadline != null) {
        long remainingMillis = deadline.timeRemaining(TimeUnit.MILLISECONDS);
        if (remainingMillis <= 0) {
          failLocked(new IOException("GraphExecute deadline expired during recovery", error));
          return false;
        }
        backoffMillis = Math.min(backoffMillis, remainingMillis);
      }
      if (backoffMillis > 0) {
        try {
          reconnectBackoffListener.run();
          lock.wait(backoffMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          failLocked(new IOException("interrupted while retrying GraphExecute", e));
          return false;
        }
      }
      if (terminal || deadlineExpired()) {
        if (!terminal) {
          failLocked(new IOException("GraphExecute deadline expired during recovery", error));
        }
        return false;
      }
      return true;
    }

    private boolean deadlineExpired() {
      Deadline deadline = stub.getCallOptions().getDeadline();
      return deadline != null && deadline.isExpired();
    }

    private void scheduleWatchdogLocked(boolean madeProgress) {
      if (terminal) {
        return;
      }
      if (madeProgress) {
        watchdogEpoch++;
      }
      if (watchdogFuture != null) {
        watchdogFuture.cancel(/* mayInterruptIfRunning= */ false);
      }
      long scheduledEpoch = watchdogEpoch;
      watchdogFuture =
          WATCHDOG_EXECUTOR.schedule(
              () -> onWatchdogExpired(scheduledEpoch),
              responseIdleTimeoutMillis,
              TimeUnit.MILLISECONDS);
    }

    private void cancelWatchdogLocked() {
      watchdogEpoch++;
      if (watchdogFuture != null) {
        watchdogFuture.cancel(/* mayInterruptIfRunning= */ false);
        watchdogFuture = null;
      }
    }

    private void onWatchdogExpired(long scheduledEpoch) {
      synchronized (lock) {
        if (terminal || scheduledEpoch != watchdogEpoch) {
          return;
        }
        watchdogFuture = null;
        if (!connected || requestObserver == null) {
          scheduleWatchdogLocked(/* madeProgress= */ false);
          return;
        }

        ClientCallStreamObserver<GraphExecuteRequest> stalledObserver = requestObserver;
        // Invalidate the stalled generation before cancellation so its synchronous onError cannot
        // race the replacement stream.
        streamGeneration++;
        connected = false;
        requestObserver = null;
        IOException watchdogError =
            new IOException(
                "GraphExecute response stream made no progress for "
                    + responseIdleTimeoutMillis
                    + " ms",
                Status.UNAVAILABLE.asException());
        lastTransportError = watchdogError;
        stalledObserver.cancel(watchdogError.getMessage(), watchdogError);

        if (recoverTransportLocked(watchdogError)) {
          if (!terminal) {
            // Opening or replaying a stream is not progress. Schedule another check in the same
            // epoch; only a validated new response advances the epoch.
            scheduleWatchdogLocked(/* madeProgress= */ false);
          }
          return;
        }
        failLocked(new IOException("GraphExecute response-idle recovery failed", watchdogError));
      }
    }

    private void resumeLocked() {
      ImmutableList<GraphExecuteRequest> requestsToReplay =
          ImmutableList.copyOf(unacknowledgedRequests.values());
      long generation = openStreamLocked(/* isResume= */ true);
      GraphExecuteRequest resumeRequest =
          GraphExecuteRequest.newBuilder()
              .setSessionId(sessionId)
              .setSequenceNumber(0)
              .setResume(
                  ResumeGraph.newBuilder()
                      .setSessionId(sessionId)
                      .setResumeToken(ByteString.copyFrom(resumeToken))
                      .setLastResponseSequence(lastResponseSequence))
              .build();
      if (!sendOnCurrentStreamLocked(generation, resumeRequest)) {
        return;
      }
      for (GraphExecuteRequest request : requestsToReplay) {
        if (!sendOnCurrentStreamLocked(generation, request)) {
          return;
        }
      }
      if (requestsClosed && isCurrentConnectedStreamLocked(generation)) {
        requestObserver.onCompleted();
      }
    }

    private void restartBeginLocked() {
      long generation = openStreamLocked(/* isResume= */ false);
      if (!sendOnCurrentStreamLocked(generation, beginRequest)) {
        return;
      }
      if (requestsClosed && isCurrentConnectedStreamLocked(generation)) {
        requestObserver.onCompleted();
      }
    }

    private static boolean isRetriableTransportError(Throwable error) {
      return switch (Status.fromThrowable(error).getCode()) {
        case UNKNOWN,
            DEADLINE_EXCEEDED,
            ABORTED,
            ALREADY_EXISTS,
            INTERNAL,
            UNAVAILABLE,
            RESOURCE_EXHAUSTED ->
            true;
        default -> false;
      };
    }

    private void onTransportCompleted(long generation) {
      synchronized (lock) {
        if (generation != streamGeneration || terminal) {
          return;
        }
        connected = false;
        requestObserver = null;
        if (!resultFuture.isDone()) {
          failLocked(new IOException("GraphExecute stream completed without a GraphResult"));
        } else if (!unacknowledgedRequests.isEmpty()) {
          failLocked(
              new IOException(
                  "GraphExecute stream completed with unacknowledged requests through "
                      + unacknowledgedRequests.lastKey()));
        } else {
          for (Map.Entry<String, SettableFuture<NodeResult>> entry : nodeResultFutures.entrySet()) {
            if (!entry.getValue().isDone()) {
              entry
                  .getValue()
                  .setException(
                      new IOException(
                          "GraphExecute completed without a NodeResult for " + entry.getKey()));
            }
          }
          terminal = true;
          cancelWatchdogLocked();
          lock.notifyAll();
        }
      }
    }

    private void failLocked(Throwable error) {
      if (terminal) {
        return;
      }
      boolean cancelTransport = connected;
      terminal = true;
      terminalError = error;
      cancelWatchdogLocked();
      lock.notifyAll();
      beginFuture.setException(error);
      resultFuture.setException(error);
      for (SettableFuture<NodeResult> future : nodeResultFutures.values()) {
        future.setException(error);
      }
      for (List<SettableFuture<Void>> futures : acknowledgementFutures.values()) {
        for (SettableFuture<Void> future : futures) {
          future.setException(error);
        }
      }
      acknowledgementFutures.clear();
      if (cancelTransport) {
        cancelTransportLocked(error.getMessage(), error);
      }
      connected = false;
      requestObserver = null;
    }

    private final class ResponseObserver
        implements ClientResponseObserver<GraphExecuteRequest, GraphExecuteResponse> {
      private final long generation;
      private final boolean isResume;

      private ResponseObserver(long generation, boolean isResume) {
        this.generation = generation;
        this.isResume = isResume;
      }

      @Override
      public void beforeStart(ClientCallStreamObserver<GraphExecuteRequest> requestStream) {
        synchronized (lock) {
          if (generation != streamGeneration || terminal) {
            requestStream.cancel("stale GraphExecute stream generation", null);
            return;
          }
          requestObserver = requestStream;
          currentStreamIsResume = isResume;
          connected = true;
        }
      }

      @Override
      public void onNext(GraphExecuteResponse response) {
        onResponse(generation, response);
      }

      @Override
      public void onError(Throwable error) {
        onTransportError(generation, error);
      }

      @Override
      public void onCompleted() {
        onTransportCompleted(generation);
      }
    }
  }

  /** Exception reported by a terminal {@link GraphStreamError}. */
  public static final class GraphExecutionException extends IOException {
    private final com.google.rpc.Status status;

    private GraphExecutionException(GraphStreamError error) {
      super(
          String.format(
              "GraphExecute failed with status %d: %s",
              error.getStatus().getCode(), error.getStatus().getMessage()));
      this.status = error.getStatus();
    }

    public com.google.rpc.Status getStatus() {
      return status;
    }
  }

  /** Exception reported when the server cannot find blobs previously announced as uploaded. */
  public static final class MissingBlobsException extends IOException {
    private final ImmutableList<Digest> digests;

    private MissingBlobsException(MissingBlobs missingBlobs) {
      super(
          String.format(
              "GraphExecute server is missing %d announced blob(s)",
              missingBlobs.getDigestsCount()));
      this.digests = ImmutableList.copyOf(missingBlobs.getDigestsList());
    }

    public ImmutableList<Digest> getDigests() {
      return digests;
    }
  }
}
