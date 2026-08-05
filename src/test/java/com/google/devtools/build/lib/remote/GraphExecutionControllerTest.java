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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.graph.v1.GraphResult;
import build.bazel.remote.execution.graph.v1.NodeResult;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ExecuteResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.common.options.OptionsParser;
import com.google.rpc.Status;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GraphExecutionControllerTest {

  @Test
  public void remoteRequireCachedIsRejected() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCommandOptions(
                    true, executionOptions(), /* keepGoing= */ false));

    assertThat(error).hasMessageThat().contains("--experimental_remote_require_cached");
  }

  @Test
  public void ordinaryOptionsAreAccepted() throws Exception {
    GraphExecutionController.validateCommandOptions(
        false, executionOptions(), /* keepGoing= */ false);
  }

  @Test
  public void localSpawnStrategyIsRejected() throws Exception {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCommandOptions(
                    false, executionOptions("--spawn_strategy=local"), /* keepGoing= */ false));

    assertThat(error).hasMessageThat().contains("--spawn_strategy");
  }

  @Test
  public void localMnemonicStrategyIsRejected() throws Exception {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCommandOptions(
                    false,
                    executionOptions("--strategy=CppCompile=local"),
                    /* keepGoing= */ false));

    assertThat(error).hasMessageThat().contains("--strategy=CppCompile");
  }

  @Test
  public void remoteStrategiesAreAccepted() throws Exception {
    GraphExecutionController.validateCommandOptions(
        false,
        executionOptions("--spawn_strategy=remote", "--strategy=CppCompile=remote"),
        /* keepGoing= */ false);
  }

  @Test
  public void keepGoingIsRejected() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCommandOptions(
                    false, executionOptions(), /* keepGoing= */ true));

    assertThat(error).hasMessageThat().contains("--keep_going");
  }

  @Test
  public void nonRemotableStaticSpawnIsRejected() {
    Spawn spawn = spawn("LocalAction", PathMapper.NOOP, ImmutableMap.of());

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateStaticSpawn(
                    spawn,
                    /* mayBeExecutedRemotely= */ false,
                    /* usesPersistentWorkerToolSignature= */ false));

    assertThat(error).hasMessageThat().contains("may not be executed remotely");
  }

  @Test
  public void persistentWorkerToolSignatureIsRejected() {
    Spawn spawn = spawn("WorkerAction", PathMapper.NOOP, ImmutableMap.of());

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateStaticSpawn(
                    spawn,
                    /* mayBeExecutedRemotely= */ true,
                    /* usesPersistentWorkerToolSignature= */ true));

    assertThat(error).hasMessageThat().contains("persistent-worker tool signatures");
  }

  @Test
  public void outputPathMappingIsRejected() {
    PathMapper mapper = mock(PathMapper.class);
    Spawn spawn = spawn("MappedAction", mapper, ImmutableMap.of());

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateStaticSpawn(
                    spawn,
                    /* mayBeExecutedRemotely= */ true,
                    /* usesPersistentWorkerToolSignature= */ false));

    assertThat(error).hasMessageThat().contains("output path mapping");
  }

  @Test
  public void inlineOutputsAreRejected() {
    Spawn spawn =
        spawn(
            "InlineAction",
            PathMapper.NOOP,
            ImmutableMap.of(ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS, "1"));

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateStaticSpawn(
                    spawn,
                    /* mayBeExecutedRemotely= */ true,
                    /* usesPersistentWorkerToolSignature= */ false));

    assertThat(error).hasMessageThat().contains("inline outputs");
  }

  @Test
  public void successfulGraphResultIsAccepted() throws Exception {
    GraphExecutionController.validateGraphResult(
        GraphResult.newBuilder()
            .setStatus(Status.newBuilder().setCode(0))
            .setCompletedNodes(3)
            .build(),
        3,
        /* failedNodeResult= */ null);
  }

  @Test
  public void graphLevelFailureIsRejected() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateGraphResult(
                    GraphResult.newBuilder()
                        .setStatus(Status.newBuilder().setCode(13).setMessage("executor failed"))
                        .setCompletedNodes(2)
                        .build(),
                    3,
                    /* failedNodeResult= */ null));

    assertThat(error).hasMessageThat().contains("executor failed");
  }

  @Test
  public void ordinaryActionFailureIsAccepted() throws Exception {
    NodeResult failedNodeResult =
        NodeResult.newBuilder()
            .setNodeId("failed")
            .setExecuteResponse(
                ExecuteResponse.newBuilder()
                    .setStatus(Status.newBuilder().setCode(0))
                    .setResult(ActionResult.newBuilder().setExitCode(1)))
            .build();

    GraphExecutionController.validateGraphResult(
        GraphResult.newBuilder()
            .setStatus(Status.newBuilder().setCode(9).setMessage("action failed"))
            .setCompletedNodes(2)
            .setFailedNodeId("failed")
            .build(),
        3,
        failedNodeResult);
  }

  @Test
  public void timeoutActionFailureIsAccepted() throws Exception {
    NodeResult failedNodeResult =
        NodeResult.newBuilder()
            .setNodeId("timed-out")
            .setExecuteResponse(
                ExecuteResponse.newBuilder()
                    .setStatus(
                        Status.newBuilder()
                            .setCode(io.grpc.Status.Code.DEADLINE_EXCEEDED.value())
                            .setMessage("deadline exceeded")))
            .build();

    GraphExecutionController.validateGraphResult(
        GraphResult.newBuilder()
            .setStatus(Status.newBuilder().setCode(9).setMessage("action failed"))
            .setCompletedNodes(1)
            .setFailedNodeId("timed-out")
            .build(),
        1,
        failedNodeResult);
  }

  @Test
  public void onlyGtestUsesDynamicGraphExecutionFallback() {
    assertThat(GraphExecutionController.isDynamicGraphTestSpawn("gtest")).isTrue();
    assertThat(GraphExecutionController.isDynamicGraphTestSpawn("test")).isFalse();
  }

  @Test
  public void gtestStaticRootsExcludeLocallyGeneratedTestExecutable() {
    ActionGraph actionGraph = mock(ActionGraph.class);
    Artifact localExecutable = mock(Artifact.DerivedArtifact.class);
    Artifact remoteOutput = mock(Artifact.DerivedArtifact.class);
    when(actionGraph.getGeneratingAction(localExecutable)).thenReturn(mock(FileWriteAction.class));
    when(actionGraph.getGeneratingAction(remoteOutput)).thenReturn(mock(SpawnAction.class));

    assertThat(
            GraphExecutionController.staticGraphRoots(
                actionGraph,
                ImmutableSet.of(localExecutable, remoteOutput),
                /* isGraphTest= */ true))
        .containsExactly(remoteOutput);
    assertThat(
            GraphExecutionController.staticGraphRoots(
                actionGraph,
                ImmutableSet.of(localExecutable, remoteOutput),
                /* isGraphTest= */ false))
        .containsExactly(localExecutable, remoteOutput);
  }

  @Test
  public void gtestStaticRootsIncludeRemoteInputsOfTestRunner() {
    ActionGraph actionGraph = mock(ActionGraph.class);
    Artifact testResult = mock(Artifact.DerivedArtifact.class);
    Artifact localExecutable = mock(Artifact.DerivedArtifact.class);
    Artifact remoteRunfile = mock(Artifact.DerivedArtifact.class);
    TestRunnerAction testRunnerAction = mock(TestRunnerAction.class);
    when(actionGraph.getGeneratingAction(testResult)).thenReturn(testRunnerAction);
    when(testRunnerAction.getInputs())
        .thenReturn(
            NestedSetBuilder.<Artifact>stableOrder()
                .add(localExecutable)
                .add(remoteRunfile)
                .build());
    when(actionGraph.getGeneratingAction(localExecutable)).thenReturn(mock(FileWriteAction.class));
    when(actionGraph.getGeneratingAction(remoteRunfile)).thenReturn(mock(SpawnAction.class));

    assertThat(
            GraphExecutionController.staticGraphRoots(
                actionGraph, ImmutableSet.of(testResult), /* isGraphTest= */ true))
        .containsExactly(remoteRunfile);
  }

  @Test
  public void cachedNonzeroResultIsRejectedBeforeImport() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCachedGraphResult(
                    /* cacheHit= */ true,
                    /* exitCode= */ 1,
                    /* missingMandatoryOutput= */ false,
                    "action-id"));

    assertThat(error).hasMessageThat().contains("cannot yet retry a stale cached subgraph");
    assertThat(error).hasMessageThat().contains("--remote_accept_cached=false");
  }

  @Test
  public void cachedResultMissingMandatoryOutputIsRejectedBeforeImport() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateCachedGraphResult(
                    /* cacheHit= */ true,
                    /* exitCode= */ 0,
                    /* missingMandatoryOutput= */ true,
                    "action-id"));

    assertThat(error).hasMessageThat().contains("stale or incomplete");
  }

  @Test
  public void noncachedActionFailureIsAcceptedForImport() throws Exception {
    GraphExecutionController.validateCachedGraphResult(
        /* cacheHit= */ false, /* exitCode= */ 1, /* missingMandatoryOutput= */ false, "action-id");
  }

  @Test
  public void failedNodeWithSuccessfulActionResultIsRejected() {
    NodeResult successfulNodeResult =
        NodeResult.newBuilder()
            .setNodeId("not-failed")
            .setExecuteResponse(
                ExecuteResponse.newBuilder()
                    .setStatus(Status.newBuilder().setCode(0))
                    .setResult(ActionResult.newBuilder().setExitCode(0)))
            .build();

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateGraphResult(
                    GraphResult.newBuilder()
                        .setStatus(Status.newBuilder().setCode(9).setMessage("action failed"))
                        .setCompletedNodes(1)
                        .setFailedNodeId("not-failed")
                        .build(),
                    1,
                    successfulNodeResult));

    assertThat(error).hasMessageThat().contains("failed at node not-failed");
  }

  @Test
  public void incompleteGraphResultIsRejected() {
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                GraphExecutionController.validateGraphResult(
                    GraphResult.newBuilder()
                        .setStatus(Status.newBuilder().setCode(0))
                        .setCompletedNodes(2)
                        .build(),
                    3,
                    /* failedNodeResult= */ null));

    assertThat(error).hasMessageThat().contains("completed 2 nodes, expected 3");
  }

  private static Spawn spawn(
      String mnemonic, PathMapper pathMapper, ImmutableMap<String, String> executionInfo) {
    Spawn spawn = mock(Spawn.class);
    ActionExecutionMetadata metadata = mock(ActionExecutionMetadata.class);
    when(metadata.prettyPrint()).thenReturn(mnemonic);
    when(spawn.getMnemonic()).thenReturn(mnemonic);
    when(spawn.getResourceOwner()).thenReturn(metadata);
    when(spawn.getPathMapper()).thenReturn(pathMapper);
    when(spawn.getExecutionInfo()).thenReturn(executionInfo);
    return spawn;
  }

  private static ExecutionOptions executionOptions(String... args) {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(ExecutionOptions.class).allowResidue(false).build();
    try {
      parser.parse(args);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    return parser.getOptions(ExecutionOptions.class);
  }
}
