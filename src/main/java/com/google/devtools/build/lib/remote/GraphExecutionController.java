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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy.REMOTE_CACHE_ONLY;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import build.bazel.remote.execution.graph.v1.ActionNode;
import build.bazel.remote.execution.graph.v1.BeginGraph;
import build.bazel.remote.execution.graph.v1.CommitGraph;
import build.bazel.remote.execution.graph.v1.GraphExecutionGrpc;
import build.bazel.remote.execution.graph.v1.GraphResult;
import build.bazel.remote.execution.graph.v1.InputBinding;
import build.bazel.remote.execution.graph.v1.NodeResult;
import build.bazel.remote.execution.graph.v1.ProducedInput;
import build.bazel.remote.execution.graph.v1.RootOutput;
import build.bazel.remote.execution.graph.v1.UploadedBlobs;
import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.VirtualActionInput;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutorLifecycleListener;
import com.google.devtools.build.lib.exec.SpawnExecutingEvent;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.remote.RemoteExecutionService.RemoteActionResult;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.CollectedAction;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.CollectedGraph;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.GraphValidationException;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.SourceInput;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode;
import com.google.devtools.build.lib.remote.graph.GraphExecutionClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.skyframe.EphemeralCheckIfOutputConsumed;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Owns one experimental graph-execution stream for a {@code gbuild} or {@code gtest} command.
 *
 * <p>The supported subset is intentionally strict. {@code gbuild} validates and submits the
 * complete graph before Skyframe begins action execution. {@code gtest} additionally uses
 * one-action graph streams for remotely eligible build and test spawns that are created or exposed
 * only during execution. Unsupported work aborts the command rather than falling back to REv2
 * Execute.
 */
final class GraphExecutionController implements ExecutorLifecycleListener {
  private static final int PROTOCOL_VERSION = 1;

  private final CommandEnvironment env;
  private final String commandName;
  private final RemoteOptions remoteOptions;
  private final DigestUtil digestUtil;
  private final CombinedCache combinedCache;
  private final ReferenceCountedChannel channelPool;
  @Nullable private final CallCredentials callCredentials;
  private final SettableFuture<Void> channelLease = SettableFuture.create();
  private final Map<Object, SubmittedNode> nodes = new IdentityHashMap<>();
  private final List<SubmittedNode> nodesInSubmissionOrder = new ArrayList<>();
  private final Set<GraphExecutionClient.Session> dynamicSessions = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean channelPoolReleased = new AtomicBoolean();

  @Nullable private ListenableFuture<Void> channelLeaseFuture;
  @Nullable private GraphExecutionClient graphExecutionClient;
  @Nullable private GraphExecutionClient.Session session;
  @Nullable private RemoteExecutionService remoteExecutionService;
  private volatile boolean submitted;

  GraphExecutionController(
      CommandEnvironment env,
      RemoteOptions remoteOptions,
      DigestUtil digestUtil,
      CombinedCache combinedCache,
      ReferenceCountedChannel channelPool,
      @Nullable CallCredentials callCredentials) {
    this.env = env;
    this.commandName = env.getCommandName();
    this.remoteOptions = remoteOptions;
    this.digestUtil = digestUtil;
    this.combinedCache = combinedCache;
    this.channelPool = channelPool;
    this.callCredentials = callCredentials;
  }

  @Override
  public void executorCreated() {}

  @Override
  public void executionPhaseStarting(
      @Nullable ActionGraph actionGraph,
      @Nullable Supplier<ImmutableSet<Artifact>> topLevelArtifacts,
      @Nullable EphemeralCheckIfOutputConsumed ephemeralCheckIfOutputConsumed)
      throws AbruptExitException, InterruptedException {
    if (actionGraph == null || topLevelArtifacts == null) {
      throw failure("graph execution requires separate analysis and execution phases", null);
    }

    try {
      ExecutionOptions executionOptions =
          java.util.Objects.requireNonNull(
              env.getOptions().getOptions(ExecutionOptions.class), "ExecutionOptions");
      KeepGoingOption keepGoingOption =
          java.util.Objects.requireNonNull(
              env.getOptions().getOptions(KeepGoingOption.class), "KeepGoingOption");
      validateCommandOptions(
          remoteOptions.getRemoteRequireCached(), executionOptions, keepGoingOption.getKeepGoing());
      SettableFuture<Channel> channelFuture = SettableFuture.create();
      channelLeaseFuture =
          channelPool.withChannelFuture(
              channel -> {
                channelFuture.set(channel);
                return channelLease;
              });
      Futures.addCallback(
          channelLeaseFuture,
          new FutureCallback<>() {
            @Override
            public void onSuccess(Void unused) {
              channelFuture.setException(
                  new IOException("graph channel lease completed before channel acquisition"));
            }

            @Override
            public void onFailure(Throwable error) {
              channelFuture.setException(error);
            }
          },
          directExecutor());
      Channel channel = getFromFuture(channelFuture);
      var stub = GraphExecutionGrpc.newStub(channel);
      if (callCredentials != null) {
        stub = stub.withCallCredentials(callCredentials);
      }

      GraphExecutionClient client = new GraphExecutionClient(stub);
      graphExecutionClient = client;
      boolean isGraphTest = "gtest".equals(commandName);
      ImmutableSet<Artifact> graphRoots =
          staticGraphRoots(actionGraph, topLevelArtifacts.get(), isGraphTest);
      if (graphRoots.isEmpty() && isGraphTest) {
        // A basic test may have only local analysis-time outputs (for example,
        // the generated wrapper of a sh_test). Bazel still executes those
        // normally, and the actual TestRunnerAction spawn is sent through the
        // dynamic one-node GraphExecute path below.
        submitted = true;
        return;
      }
      CollectedGraph graph;
      try {
        graph = GraphActionCollector.collect(actionGraph, graphRoots);
      } catch (GraphValidationException e) {
        if (isGraphTest && e.code() == ValidationCode.NO_ROOTS) {
          // The remaining roots are workspace-status artifacts, which the
          // collector intentionally ignores. Execute the remotely eligible
          // test build and runner spawns through one-node GraphExecute streams.
          submitted = true;
          return;
        }
        throw e;
      }
      BeginGraph.Builder begin =
          BeginGraph.newBuilder()
              .setProtocolVersion(PROTOCOL_VERSION)
              .setInstanceName(remoteOptions.getRemoteInstanceName())
              .setDigestFunction(digestUtil.getDigestFunction())
              .setInvocationId(env.getCommandId().toString());
      for (var root : graph.roots()) {
        begin.addRoots(
            RootOutput.newBuilder()
                .setNodeId(root.producerNodeId())
                .setOutputPath(root.outputPath()));
      }
      session = client.begin(UUID.randomUUID().toString(), begin.build());
      getFromFuture(session.beginFuture());

      InputMetadataProvider inputMetadataProvider = env.getFileCache();
      RemoteExecutionService service = remoteExecutionService;
      if (service == null) {
        throw new IOException("remote execution service is unavailable");
      }
      RemotePathResolver remotePathResolver = service.getBaseRemotePathResolver();
      boolean useOutputPaths = service.useOutputPathsForGraphExecution();
      TreeMap<Digest, Upload> uploads = new TreeMap<>(DigestUtil.DIGEST_COMPARATOR);
      for (CollectedAction action : graph.actions()) {
        SubmittedNode node =
            lowerAction(action, inputMetadataProvider, remotePathResolver, useOutputPaths, uploads);
        nodes.put(action.action(), node);
        nodesInSubmissionOrder.add(node);
        session.nodeResultFuture(node.actionNode().getNodeId());
      }

      RequestMetadata uploadMetadata =
          TracingMetadataUtils.buildMetadata(
              env.getBuildRequestId(), env.getCommandId().toString(), "graph-input-upload");
      RemoteActionExecutionContext uploadContext =
          RemoteActionExecutionContext.create(uploadMetadata)
              .withWriteCachePolicy(REMOTE_CACHE_ONLY);
      var uploadFutures = new java.util.ArrayList<ListenableFuture<Void>>(uploads.size());
      for (Map.Entry<Digest, Upload> entry : uploads.entrySet()) {
        uploadFutures.add(entry.getValue().upload(combinedCache, uploadContext, entry.getKey()));
      }
      getFromFuture(Futures.allAsList(uploadFutures));

      session.sendUploadedBlobs(UploadedBlobs.newBuilder().addAllDigests(uploads.keySet()).build());
      for (SubmittedNode node : nodesInSubmissionOrder) {
        session.sendAction(node.actionNode());
      }
      long commitSequence =
          session.sendCommit(
              CommitGraph.newBuilder().setExpectedActionCount(graph.actions().size()).build());
      session.close();
      getFromFuture(session.acknowledgementFuture(commitSequence));
      GraphResult graphResult = getFromFuture(session.resultFuture());
      NodeResult failedNodeResult = null;
      if (!graphResult.getFailedNodeId().isEmpty()) {
        boolean knownNode =
            nodesInSubmissionOrder.stream()
                .anyMatch(
                    node -> node.actionNode().getNodeId().equals(graphResult.getFailedNodeId()));
        if (knownNode) {
          failedNodeResult = getFromFuture(session.nodeResultFuture(graphResult.getFailedNodeId()));
        }
      }
      validateGraphResult(graphResult, graph.actions().size(), failedNodeResult);
      if (graphResult.getStatus().getCode() == Status.Code.OK.value()) {
        for (SubmittedNode node : nodesInSubmissionOrder) {
          getFromFuture(session.nodeResultFuture(node.actionNode().getNodeId()));
        }
      }
      submitted = true;
    } catch (InterruptedException e) {
      cancelSession("graph execution setup was interrupted", e);
      throw e;
    } catch (Exception e) {
      cancelSession("graph execution setup failed", e);
      throw failure("graph execution setup failed: " + e.getMessage(), e);
    }
  }

  private SubmittedNode lowerAction(
      CollectedAction collected,
      InputMetadataProvider inputMetadataProvider,
      RemotePathResolver baseRemotePathResolver,
      boolean useOutputPaths,
      TreeMap<Digest, Upload> uploads)
      throws Exception {
    Spawn spawn =
        collected.action().getSpawnForGraphExecution(inputMetadataProvider, env.getClientEnv());
    RemoteExecutionService service = remoteExecutionService;
    if (service == null) {
      throw new IOException("remote execution service is unavailable");
    }
    validateStaticSpawn(
        spawn,
        service.mayBeExecutedRemotely(spawn),
        service.usesPersistentWorkerToolSignature(spawn));
    RemotePathResolver remotePathResolver =
        RemotePathResolver.createMapped(
            baseRemotePathResolver, env.getExecRoot(), spawn.getPathMapper());
    Platform platform = PlatformUtils.getPlatformProto(spawn, remoteOptions);
    var command =
        RemoteExecutionService.buildCommand(
            useOutputPaths,
            spawn.getOutputFiles(),
            spawn.getArguments(),
            spawn.getEnvironment(),
            platform,
            remotePathResolver,
            /* spawnScrubber= */ null,
            spawn.getExecutionPlatform());

    TreeMap<String, InputBinding> bindings = new TreeMap<>();
    for (var input : collected.inputs()) {
      if (input instanceof SourceInput sourceInput) {
        addSourceBinding(
            sourceInput.artifact(),
            toRemoteInputPath(remotePathResolver, sourceInput.artifact()),
            inputMetadataProvider,
            bindings,
            uploads);
      } else if (input instanceof GraphActionCollector.ProducedInput producedInput) {
        String execPath = toRemoteInputPath(remotePathResolver, producedInput.artifact());
        bindings.put(
            execPath,
            InputBinding.newBuilder()
                .setExecPath(execPath)
                .setIsExecutable(true)
                .setProduced(
                    ProducedInput.newBuilder()
                        .setProducerNodeId(producedInput.producerNodeId())
                        .setOutputPath(
                            remotePathResolver.localPathToOutputPath(producedInput.artifact())))
                .build());
      }
    }

    for (ActionInput input : spawn.getInputFiles().flatten()) {
      if (input instanceof VirtualActionInput virtualInput) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        virtualInput.writeTo(out);
        ByteString contents = ByteString.copyFrom(out.toByteArray());
        Digest digest = digestUtil.compute(contents);
        uploads.putIfAbsent(digest, Upload.forBlob(contents));
        String execPath = toRemoteInputPath(remotePathResolver, input);
        bindings.put(
            execPath,
            InputBinding.newBuilder()
                .setExecPath(execPath)
                // Match MerkleTreeComputer: Bazel deliberately marks every regular input
                // executable when constructing the REAPI Directory.
                .setIsExecutable(true)
                .setSourceDigest(digest)
                .build());
      } else if (!bindings.containsKey(toRemoteInputPath(remotePathResolver, input))) {
        throw new IOException(
            "spawn materialization introduced an undeclared non-virtual input: "
                + input.getExecPathString());
      }
    }

    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(
            env.getBuildRequestId(),
            env.getCommandId().toString(),
            collected.nodeId(),
            spawn.getMnemonic(),
            spawn.getTargetLabel() != null ? spawn.getTargetLabel().getCanonicalForm() : null,
            spawn.getConfigurationChecksum());
    java.time.Duration timeout = Spawns.getTimeout(spawn);
    ActionNode.Builder node =
        ActionNode.newBuilder()
            .setNodeId(collected.nodeId())
            .setCommand(command)
            .addAllInputs(bindings.values())
            .addAllSchedulingDependencies(collected.schedulingDependencies())
            .setDoNotCache(!Spawns.mayBeCachedRemotely(spawn))
            .setSalt(RemoteExecutionService.buildSalt(spawn, /* spawnScrubber= */ null))
            .setSkipCacheLookup(
                !remoteOptions.getRemoteAcceptCached() || !Spawns.mayBeCachedRemotely(spawn))
            .setRequestMetadata(requestMetadata);
    if (!timeout.isZero()) {
      // Match Utils.buildAction, including its intentional whole-second precision.
      node.setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(timeout.toSeconds()));
    }
    if (platform != null) {
      node.setPlatform(platform);
    }
    if (remoteOptions.getRemoteResultCachePriority() != 0) {
      node.getResultsCachePolicyBuilder().setPriority(remoteOptions.getRemoteResultCachePriority());
    }
    if (remoteOptions.getRemoteExecutionPriority() != 0) {
      node.getExecutionPolicyBuilder().setPriority(remoteOptions.getRemoteExecutionPriority());
    }
    return new SubmittedNode(spawn, node.build());
  }

  private static String toRemoteInputPath(
      RemotePathResolver remotePathResolver, ActionInput input) {
    return remotePathResolver
        .getWorkingDirectory()
        .getRelative(input.getExecPath())
        .getPathString();
  }

  private void addSourceBinding(
      Artifact artifact,
      String execPath,
      InputMetadataProvider inputMetadataProvider,
      TreeMap<String, InputBinding> bindings,
      TreeMap<Digest, Upload> uploads)
      throws IOException {
    FileArtifactValue metadata = inputMetadataProvider.getInputMetadata(artifact);
    if (metadata == null || metadata.getDigest() == null) {
      throw new IOException("source input metadata is unavailable: " + execPath);
    }
    Digest digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());
    uploads.putIfAbsent(digest, Upload.forFile(artifact.getPath()));
    bindings.put(
        execPath,
        InputBinding.newBuilder()
            .setExecPath(execPath)
            // Match MerkleTreeComputer rather than consulting mutable filesystem permissions.
            .setIsExecutable(true)
            .setSourceDigest(digest)
            .build());
  }

  @Override
  public void executionPhaseEnding() {
    close();
  }

  @VisibleForTesting
  static void validateCommandOptions(
      boolean remoteRequireCached, ExecutionOptions executionOptions, boolean keepGoing)
      throws IOException {
    if (remoteRequireCached) {
      throw new IOException(
          "graph execution does not support --experimental_remote_require_cached because the "
              + "GraphExecute server cannot enforce cache-only execution");
    }
    if (keepGoing) {
      throw new IOException(
          "graph execution does not support --keep_going until independently runnable nodes "
              + "complete after an action failure");
    }
    validateStrategyList("--spawn_strategy", executionOptions.getSpawnStrategy());
    validateStrategyList("--genrule_strategy", executionOptions.getGenruleStrategy());
    for (Map.Entry<String, List<String>> strategy : executionOptions.getStrategy()) {
      validateStrategyList("--strategy=" + strategy.getKey(), strategy.getValue());
    }
    for (Map.Entry<?, List<String>> strategy : executionOptions.getStrategyByRegexp()) {
      validateStrategyList("--strategy_regexp", strategy.getValue());
    }
    for (Map.Entry<?, List<String>> strategy :
        executionOptions.getAllowedStrategiesByExecPlatform()) {
      validateStrategyList("--allowed_strategies_by_exec_platform", strategy.getValue());
    }
    if (!executionOptions.getTestStrategy().isEmpty()
        && !executionOptions.getTestStrategy().equals("remote")) {
      throw new IOException(
          "graph execution requires --test_strategy=remote, got "
              + executionOptions.getTestStrategy());
    }
  }

  private static void validateStrategyList(String option, List<String> strategies)
      throws IOException {
    if (!strategies.isEmpty() && (strategies.size() != 1 || !strategies.get(0).equals("remote"))) {
      throw new IOException(
          "graph execution requires " + option + " to select only the remote strategy");
    }
  }

  @VisibleForTesting
  static void validateStaticSpawn(
      Spawn spawn, boolean mayBeExecutedRemotely, boolean usesPersistentWorkerToolSignature)
      throws IOException {
    if (!mayBeExecutedRemotely) {
      throw new IOException(
          "spawn may not be executed remotely: "
              + spawn.getMnemonic()
              + " "
              + spawn.getResourceOwner().prettyPrint());
    }
    if (usesPersistentWorkerToolSignature) {
      throw new IOException(
          "graph execution does not yet support remote persistent-worker tool signatures: "
              + spawn.getMnemonic());
    }
    if (!spawn.getPathMapper().isNoop()) {
      throw new IOException(
          "graph execution does not yet support output path mapping: " + spawn.getMnemonic());
    }
    if (spawn
        .getExecutionInfo()
        .containsKey(ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS)) {
      throw new IOException(
          "graph execution does not yet support inline outputs: " + spawn.getMnemonic());
    }
  }

  @VisibleForTesting
  static void validateGraphResult(
      GraphResult result, long expectedCompletedNodes, @Nullable NodeResult failedNodeResult)
      throws IOException {
    if (result.getStatus().getCode() == 0) {
      if (!result.getFailedNodeId().isEmpty()) {
        throw new IOException(
            "successful graph execution named a failed node: " + result.getFailedNodeId());
      }
      if (result.getCompletedNodes() != expectedCompletedNodes) {
        throw new IOException(
            "graph execution completed "
                + result.getCompletedNodes()
                + " nodes, expected "
                + expectedCompletedNodes);
      }
      return;
    }
    if (result.getFailedNodeId().isEmpty()) {
      throw new IOException(
          "graph execution failed with status "
              + result.getStatus().getCode()
              + ": "
              + result.getStatus().getMessage());
    }
    if (failedNodeResult == null
        || !result.getFailedNodeId().equals(failedNodeResult.getNodeId())) {
      throw new IOException(
          "graph execution named an unknown failed node: " + result.getFailedNodeId());
    }
    if (!isOrdinaryActionFailure(failedNodeResult)) {
      throw new IOException(
          "graph execution failed at node "
              + result.getFailedNodeId()
              + " with status "
              + result.getStatus().getCode()
              + ": "
              + result.getStatus().getMessage());
    }
    if (result.getCompletedNodes() == 0 || result.getCompletedNodes() > expectedCompletedNodes) {
      throw new IOException(
          "failed graph execution reported "
              + result.getCompletedNodes()
              + " completed nodes out of "
              + expectedCompletedNodes);
    }
  }

  private static boolean isOrdinaryActionFailure(NodeResult nodeResult) {
    if (!nodeResult.hasExecuteResponse()) {
      return false;
    }
    var response = nodeResult.getExecuteResponse();
    return response.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED.value()
        || (response.getStatus().getCode() == Status.Code.OK.value()
            && response.hasResult()
            && response.getResult().getExitCode() != 0);
  }

  void close() {
    GraphExecutionClient.Session session = this.session;
    if (session != null) {
      session.cancel("Bazel execution phase ended", null);
    }
    for (GraphExecutionClient.Session dynamicSession : dynamicSessions) {
      dynamicSession.cancel("Bazel execution phase ended", null);
    }
    channelLease.set(null);
    ListenableFuture<Void> channelLeaseFuture = this.channelLeaseFuture;
    if (channelLeaseFuture != null && !channelLeaseFuture.isDone()) {
      channelLeaseFuture.cancel(true);
    }
    if (channelPoolReleased.compareAndSet(false, true)) {
      channelPool.release();
    }
  }

  void setRemoteExecutionService(RemoteExecutionService remoteExecutionService) {
    this.remoteExecutionService = remoteExecutionService;
  }

  void registerSpawnStrategy(
      SpawnStrategyRegistry.Builder registryBuilder, ExecutionOptions executionOptions) {
    registryBuilder.registerStrategy(
        new RemoteSpawnStrategy(new GraphSpawnRunner(), executionOptions), "remote");
  }

  private void cancelSession(String message, @Nullable Throwable cause) {
    GraphExecutionClient.Session session = this.session;
    if (session != null) {
      session.cancel(message, cause);
    }
    channelLease.setException(cause != null ? cause : new IOException(message));
  }

  private static AbruptExitException failure(String message, @Nullable Throwable cause) {
    FailureDetail detail =
        FailureDetail.newBuilder()
            .setMessage(message)
            .setRemoteExecution(
                RemoteExecution.newBuilder().setCode(RemoteExecution.Code.REMOTE_EXECUTION_UNKNOWN))
            .build();
    return cause == null
        ? new AbruptExitException(DetailedExitCode.of(detail))
        : new AbruptExitException(DetailedExitCode.of(detail), cause);
  }

  private record SubmittedNode(Spawn spawn, ActionNode actionNode) {}

  private final class GraphSpawnRunner implements SpawnRunner {
    private static final SpawnExecutingEvent EXECUTING_EVENT =
        SpawnExecutingEvent.create("graph-remote");

    @Override
    public SpawnResult exec(Spawn spawn, SpawnExecutionContext context)
        throws com.google.devtools.build.lib.actions.ExecException,
            InterruptedException,
            IOException {
      if (!submitted) {
        throw new IOException("graph execution was not submitted before spawn execution");
      }
      SubmittedNode node = nodes.get(spawn.getResourceOwner());
      if (node == null) {
        if (isDynamicGraphTestSpawn(commandName)) {
          return execDynamic(spawn, context);
        }
        throw new IOException(
            "spawn was not declared in the graph: "
                + spawn.getMnemonic()
                + " "
                + spawn.getResourceOwner().prettyPrint());
      }
      GraphExecutionClient.Session session = GraphExecutionController.this.session;
      if (session == null) {
        throw new IOException("graph execution session is unavailable");
      }
      context.report(EXECUTING_EVENT);
      NodeResult nodeResult =
          getFromFuture(session.nodeResultFuture(node.actionNode().getNodeId()));

      RemoteExecutionService service = remoteExecutionService;
      if (service == null) {
        throw new IOException("remote execution service is unavailable");
      }
      RemoteAction remoteAction =
          service.buildRemoteAction(
              spawn,
              context,
              com.google.devtools.build.lib.remote.merkletree.MerkleTreeComputer.BlobPolicy
                  .DISCARD);
      if (!nodeResult.getActionDigest().equals(remoteAction.getActionKey().digest())) {
        throw new IOException(
            "graph node returned action digest "
                + nodeResult.getActionDigest()
                + ", but Bazel reconstructed "
                + remoteAction.getActionKey().digest());
      }
      return importResult(spawn, context, remoteAction, nodeResult);
    }

    private SpawnResult execDynamic(Spawn spawn, SpawnExecutionContext context)
        throws com.google.devtools.build.lib.actions.ExecException,
            InterruptedException,
            IOException {
      RemoteExecutionService service = remoteExecutionService;
      GraphExecutionClient client = graphExecutionClient;
      if (service == null || client == null) {
        throw new IOException("graph execution service is unavailable");
      }
      if (!service.mayBeExecutedRemotely(spawn)) {
        throw new IOException(
            "spawn may not be executed remotely: "
                + spawn.getMnemonic()
                + " "
                + spawn.getResourceOwner().prettyPrint());
      }
      RemoteAction remoteAction = service.buildRemoteAction(spawn, context);
      service.uploadInputsIfNotPresent(remoteAction, /* force= */ false);

      Action action = remoteAction.getAction();
      String nodeId = remoteAction.getActionKey().digest().getHash();
      BeginGraph begin =
          BeginGraph.newBuilder()
              .setProtocolVersion(PROTOCOL_VERSION)
              .setInstanceName(remoteOptions.getRemoteInstanceName())
              .setDigestFunction(digestUtil.getDigestFunction())
              .setInvocationId(env.getCommandId().toString())
              .build();
      GraphExecutionClient.Session dynamicSession =
          client.begin(UUID.randomUUID().toString(), begin);
      dynamicSessions.add(dynamicSession);
      try {
        getFromFuture(dynamicSession.beginFuture());
        dynamicSession.sendUploadedBlobs(
            UploadedBlobs.newBuilder().addDigests(action.getInputRootDigest()).build());
        ActionNode.Builder node =
            ActionNode.newBuilder()
                .setNodeId(nodeId)
                .setCommand(remoteAction.getCommand())
                .setInputRootDigest(action.getInputRootDigest())
                .setDoNotCache(action.getDoNotCache())
                .setSalt(action.getSalt())
                .setSkipCacheLookup(
                    !remoteOptions.getRemoteAcceptCached() || action.getDoNotCache())
                .setRequestMetadata(
                    remoteAction.getRemoteActionExecutionContext().getRequestMetadata());
        if (action.hasTimeout()) {
          node.setTimeout(action.getTimeout());
        }
        if (action.hasPlatform()) {
          node.setPlatform(action.getPlatform());
        }
        if (remoteOptions.getRemoteResultCachePriority() != 0) {
          node.getResultsCachePolicyBuilder()
              .setPriority(remoteOptions.getRemoteResultCachePriority());
        }
        if (remoteOptions.getRemoteExecutionPriority() != 0) {
          node.getExecutionPolicyBuilder().setPriority(remoteOptions.getRemoteExecutionPriority());
        }
        dynamicSession.sendAction(node.build());
        long commitSequence =
            dynamicSession.sendCommit(CommitGraph.newBuilder().setExpectedActionCount(1).build());
        dynamicSession.close();
        getFromFuture(dynamicSession.acknowledgementFuture(commitSequence));
        context.report(EXECUTING_EVENT);
        NodeResult nodeResult = getFromFuture(dynamicSession.nodeResultFuture(nodeId));
        validateGraphResult(getFromFuture(dynamicSession.resultFuture()), 1, nodeResult);
        if (!nodeResult.getActionDigest().equals(remoteAction.getActionKey().digest())) {
          throw new IOException(
              "dynamic graph node returned an unexpected action digest: "
                  + nodeResult.getActionDigest());
        }
        return importResult(spawn, context, remoteAction, nodeResult);
      } catch (InterruptedException | IOException | RuntimeException e) {
        dynamicSession.cancel("dynamic graph execution failed", e);
        throw e;
      } finally {
        dynamicSession.cancel("dynamic graph execution completed", null);
        dynamicSessions.remove(dynamicSession);
      }
    }

    private SpawnResult importResult(
        Spawn spawn,
        SpawnExecutionContext context,
        RemoteAction remoteAction,
        NodeResult nodeResult)
        throws com.google.devtools.build.lib.actions.ExecException,
            IOException,
            InterruptedException {
      if (!nodeResult.hasExecuteResponse()) {
        throw new IOException(
            "graph node completed without an ExecuteResponse: " + remoteAction.getActionId());
      }
      var response = nodeResult.getExecuteResponse();
      SpawnMetrics.Builder spawnMetricsBuilder =
          SpawnMetrics.Builder.forRemoteExec()
              .setInputBytes(remoteAction.getInputBytes())
              .setInputFiles(remoteAction.getInputFiles());
      if (response.hasResult()) {
        RemoteSpawnRunner.spawnMetricsAccounting(
            spawnMetricsBuilder, response.getResult().getExecutionMetadata());
      }
      SpawnMetrics spawnMetrics = spawnMetricsBuilder.build();
      context.setDigest(digestUtil.asSpawnLogProto(remoteAction.getActionKey()));
      if (response.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED.value()) {
        RemoteActionResult timeoutResult =
            response.hasResult() ? RemoteActionResult.createFromResponse(response) : null;
        RemoteExecutionService service = remoteExecutionService;
        if (timeoutResult != null) {
          if (service == null) {
            throw new IOException("remote execution service is unavailable");
          }
          service.downloadOutputs(remoteAction, timeoutResult);
        }
        return timeoutSpawnResult(timeoutResult, spawnMetrics);
      }
      if (response.getStatus().getCode() != Status.Code.OK.value()) {
        throw new IOException("graph node execution failed: " + response.getStatus().getMessage());
      }
      if (!response.hasResult()) {
        throw new IOException(
            "graph node completed without an ExecuteResponse ActionResult: "
                + remoteAction.getActionId());
      }
      RemoteExecutionService service = remoteExecutionService;
      if (service == null) {
        throw new IOException("remote execution service is unavailable");
      }
      RemoteActionResult result = RemoteActionResult.createFromResponse(response);
      validateCachedGraphResult(
          result.cacheHit(),
          result.getExitCode(),
          result.maybeGetMissingMandatoryOutput(remoteAction).isPresent(),
          remoteAction.getActionId());
      InMemoryOutput inMemoryOutput;
      try {
        inMemoryOutput = service.downloadOutputs(remoteAction, result);
      } catch (BulkTransferException e) {
        if (result.cacheHit() && e.allCausedByCacheNotFoundException()) {
          throw staleCachedResult(remoteAction.getActionId(), e);
        }
        throw e;
      }
      return Utils.createSpawnResult(
          digestUtil,
          remoteAction.getActionKey(),
          result.getExitCode(),
          result.cacheHit(),
          getName(),
          inMemoryOutput,
          result.getExecutionMetadata().getExecutionStartTimestamp(),
          result.getExecutionMetadata().getExecutionCompletedTimestamp(),
          spawnMetrics,
          spawn.getMnemonic());
    }

    private SpawnResult timeoutSpawnResult(
        @Nullable RemoteActionResult result, SpawnMetrics spawnMetrics) {
      SpawnResult.Builder builder =
          new SpawnResult.Builder()
              .setRunnerName(getName())
              .setStatus(SpawnResult.Status.TIMEOUT)
              .setExitCode(SpawnResult.POSIX_TIMEOUT_EXIT_CODE)
              .setFailureDetail(
                  FailureDetail.newBuilder()
                      .setMessage("remote spawn timed out")
                      .setSpawn(
                          com.google.devtools.build.lib.server.FailureDetails.Spawn.newBuilder()
                              .setCode(
                                  com.google.devtools.build.lib.server.FailureDetails.Spawn.Code
                                      .TIMEOUT))
                      .build())
              .setSpawnMetrics(spawnMetrics);
      if (result != null) {
        builder
            .setWallTimeInMs(
                (int)
                    java.time.Duration.between(
                            Utils.timestampToInstant(
                                result.getExecutionMetadata().getExecutionStartTimestamp()),
                            Utils.timestampToInstant(
                                result.getExecutionMetadata().getExecutionCompletedTimestamp()))
                        .toMillis())
            .setStartTime(
                Utils.timestampToInstant(
                    result.getExecutionMetadata().getExecutionStartTimestamp()));
      }
      return builder.build();
    }

    @Override
    public boolean canExec(Spawn spawn) {
      RemoteExecutionService service = remoteExecutionService;
      return service != null && service.mayBeExecutedRemotely(spawn);
    }

    @Override
    public boolean handlesCaching() {
      return true;
    }

    @Override
    public String getName() {
      return "graph-remote";
    }
  }

  private static IOException staleCachedResult(String actionId, @Nullable Throwable cause) {
    String message =
        "cached graph result for action "
            + actionId
            + " is stale or incomplete; graph execution cannot yet retry a stale cached "
            + "subgraph (retry with --remote_accept_cached=false)";
    return cause == null ? new IOException(message) : new IOException(message, cause);
  }

  @VisibleForTesting
  static void validateCachedGraphResult(
      boolean cacheHit, int exitCode, boolean missingMandatoryOutput, String actionId)
      throws IOException {
    if (cacheHit && (exitCode != 0 || missingMandatoryOutput)) {
      throw staleCachedResult(actionId, null);
    }
  }

  @VisibleForTesting
  static boolean isDynamicGraphTestSpawn(String commandName) {
    return "gtest".equals(commandName);
  }

  @VisibleForTesting
  static ImmutableSet<Artifact> staticGraphRoots(
      ActionGraph actionGraph, ImmutableSet<Artifact> topLevelArtifacts, boolean isGraphTest) {
    if (!isGraphTest) {
      return topLevelArtifacts;
    }
    ImmutableSet.Builder<Artifact> roots = ImmutableSet.builder();
    for (Artifact artifact : topLevelArtifacts) {
      if (artifact.isRunfilesTree()) {
        continue;
      }
      var generatingAction = actionGraph.getGeneratingAction(artifact);
      if (generatingAction instanceof TestRunnerAction testRunnerAction) {
        // TestRunnerAction itself is projected only from its actual runtime
        // spawn. Its inputs still expose the remotely generated build outputs
        // that must be completed by the static graph before test execution.
        for (Artifact input : testRunnerAction.getInputs().toList()) {
          if (actionGraph.getGeneratingAction(input) instanceof SpawnAction) {
            roots.add(input);
          }
        }
      } else if (!(generatingAction instanceof FileWriteAction)) {
        roots.add(artifact);
      }
    }
    return roots.build();
  }

  private sealed interface Upload {
    ListenableFuture<Void> upload(
        CombinedCache cache, RemoteActionExecutionContext context, Digest digest);

    static Upload forFile(com.google.devtools.build.lib.vfs.Path path) {
      return new FileUpload(path);
    }

    static Upload forBlob(ByteString data) {
      return new BlobUpload(data);
    }
  }

  private record FileUpload(com.google.devtools.build.lib.vfs.Path path) implements Upload {
    @Override
    public ListenableFuture<Void> upload(
        CombinedCache cache, RemoteActionExecutionContext context, Digest digest) {
      return cache.uploadFile(context, digest, path);
    }
  }

  private record BlobUpload(ByteString data) implements Upload {
    @Override
    public ListenableFuture<Void> upload(
        CombinedCache cache, RemoteActionExecutionContext context, Digest digest) {
      return cache.uploadBlob(context, digest, data);
    }
  }
}
