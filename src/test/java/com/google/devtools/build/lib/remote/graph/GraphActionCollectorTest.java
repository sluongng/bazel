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
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.ACTION_CYCLE;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.DYNAMIC_INPUTS;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.MISSING_PRODUCER;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.SOURCE_ROOT;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.UNSUPPORTED_ACTION;
import static com.google.devtools.build.lib.remote.graph.GraphActionCollector.ValidationCode.UNSUPPORTED_ARTIFACT;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.CollectedAction;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.CollectedGraph;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.GraphValidationException;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.ProducedInput;
import com.google.devtools.build.lib.remote.graph.GraphActionCollector.SourceInput;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GraphActionCollector}. */
@RunWith(JUnit4.class)
public final class GraphActionCollectorTest {
  private final Map<Artifact, ActionAnalysisMetadata> producers = new HashMap<>();
  private final ActionGraph actionGraph = producers::get;
  private final InMemoryFileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private ArtifactRoot sourceRoot;
  private ArtifactRoot outputRoot;

  @Before
  public void setUp() {
    Path execRoot = fileSystem.getPath("/exec");
    sourceRoot = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));
    outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out");
  }

  @Test
  public void collect_buildsRootClosureAndClassifiesInputs() throws Exception {
    Artifact source = source("pkg/source.txt");
    Artifact intermediate = output("pkg/intermediate.txt");
    Artifact result = output("pkg/result.txt");
    SpawnAction producer = action(ImmutableList.of(source), ImmutableList.of(intermediate));
    SpawnAction consumer = action(ImmutableList.of(intermediate), ImmutableList.of(result));
    register(producer);
    register(consumer);

    CollectedGraph graph = GraphActionCollector.collect(actionGraph, ImmutableList.of(result));

    assertThat(graph.actions()).hasSize(2);
    CollectedAction first = graph.actions().get(0);
    CollectedAction second = graph.actions().get(1);
    assertThat(first.action()).isSameInstanceAs(producer);
    assertThat(second.action()).isSameInstanceAs(consumer);
    assertThat(first.inputs()).containsExactly(new SourceInput(source));
    assertThat(second.inputs())
        .containsExactly(
            new ProducedInput(intermediate, first.nodeId(), intermediate.getExecPathString()));
    assertThat(graph.roots().get(0).producerNodeId()).isEqualTo(second.nodeId());
    assertThat(graph.roots().get(0).outputPath()).isEqualTo(result.getExecPathString());
  }

  @Test
  public void collect_isDeterministicAcrossRootAndOutputOrder() throws Exception {
    Artifact source = source("pkg/source.txt");
    Artifact firstOutput = output("pkg/a.txt");
    Artifact secondOutput = output("pkg/b.txt");
    SpawnAction action =
        action(ImmutableList.of(source), ImmutableList.of(secondOutput, firstOutput));
    register(action);

    CollectedGraph forward =
        GraphActionCollector.collect(
            actionGraph, ImmutableList.of(firstOutput, secondOutput, firstOutput));
    CollectedGraph reverse =
        GraphActionCollector.collect(actionGraph, ImmutableList.of(secondOutput, firstOutput));

    assertThat(forward.actions().get(0).nodeId()).isEqualTo(reverse.actions().get(0).nodeId());
    assertThat(forward.roots().stream().map(GraphActionCollector.Root::outputPath))
        .containsExactly(firstOutput.getExecPathString(), secondOutput.getExecPathString())
        .inOrder();
    assertThat(reverse.roots()).isEqualTo(forward.roots());
  }

  @Test
  public void collect_includesDerivedSchedulingDependency() throws Exception {
    Artifact orderOnlyOutput = output("pkg/order-only.txt");
    Artifact result = output("pkg/result.txt");
    SpawnAction orderOnlyProducer = action(ImmutableList.of(), ImmutableList.of(orderOnlyOutput));
    register(orderOnlyProducer);
    SpawnAction consumer = spy(action(ImmutableList.of(), ImmutableList.of(result)));
    when(consumer.getSchedulingDependencies())
        .thenReturn(NestedSetBuilder.<Artifact>stableOrder().add(orderOnlyOutput).build());
    register(consumer);

    CollectedGraph graph = GraphActionCollector.collect(actionGraph, ImmutableList.of(result));

    assertThat(graph.actions().stream().map(CollectedAction::action))
        .containsExactly(orderOnlyProducer, consumer)
        .inOrder();
    assertThat(graph.actions().get(1).schedulingDependencies())
        .containsExactly(graph.actions().get(0).nodeId());
    assertThat(graph.actions().get(1).inputs()).isEmpty();
  }

  @Test
  public void collect_workspaceStatusArtifactsAreEagerInputsNotRoots() throws Exception {
    Artifact source = source("pkg/source.txt");
    Artifact stableStatus = output("stable-status.txt");
    Artifact volatileStatus = output("volatile-status.txt");
    Artifact result = output("pkg/result.txt");
    WorkspaceStatusAction statusAction = mock(WorkspaceStatusAction.class);
    producers.put(stableStatus, statusAction);
    producers.put(volatileStatus, statusAction);
    SpawnAction action =
        action(ImmutableList.of(source, stableStatus, volatileStatus), ImmutableList.of(result));
    register(action);

    CollectedGraph graph =
        GraphActionCollector.collect(
            actionGraph, ImmutableList.of(stableStatus, result, volatileStatus));

    assertThat(graph.roots().stream().map(GraphActionCollector.Root::artifact))
        .containsExactly(result);
    assertThat(graph.actions()).hasSize(1);
    assertThat(graph.actions().get(0).inputs())
        .containsExactly(
            new SourceInput(source),
            new SourceInput(stableStatus),
            new SourceInput(volatileStatus));
  }

  @Test
  public void collect_sourceRootFailsClosed() {
    Artifact source = source("pkg/source.txt");

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(source)));

    assertThat(e.code()).isEqualTo(SOURCE_ROOT);
    assertThat(e).hasMessageThat().contains(source.getExecPathString());
  }

  @Test
  public void collect_missingDerivedProducerFailsClosed() {
    Artifact result = output("pkg/result.txt");

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(result)));

    assertThat(e.code()).isEqualTo(MISSING_PRODUCER);
  }

  @Test
  public void collect_nonSpawnProducerFailsClosed() {
    Artifact result = output("pkg/result.txt");
    producers.put(
        result, new ActionsTestUtil.MockAction(ImmutableList.of(), ImmutableSet.of(result)));

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(result)));

    assertThat(e.code()).isEqualTo(UNSUPPORTED_ACTION);
    assertThat(e).hasMessageThat().contains(ActionsTestUtil.MockAction.class.getName());
  }

  @Test
  public void collect_inputDiscoveringSpawnFailsClosed() {
    Artifact result = output("pkg/result.txt");
    SpawnAction action = spy(action(ImmutableList.of(), ImmutableList.of(result)));
    when(action.discoversInputs()).thenReturn(true);
    register(action);

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(result)));

    assertThat(e.code()).isEqualTo(DYNAMIC_INPUTS);
  }

  @Test
  public void collect_treeArtifactFailsClosed() {
    Artifact tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputRoot, "pkg/tree");
    SpawnAction action = action(ImmutableList.of(), ImmutableList.of(tree));
    register(action);

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(tree)));

    assertThat(e.code()).isEqualTo(UNSUPPORTED_ARTIFACT);
  }

  @Test
  public void collect_actionCycleFailsClosed() {
    Artifact firstOutput = output("pkg/first.txt");
    Artifact secondOutput = output("pkg/second.txt");
    SpawnAction first = action(ImmutableList.of(secondOutput), ImmutableList.of(firstOutput));
    SpawnAction second = action(ImmutableList.of(firstOutput), ImmutableList.of(secondOutput));
    register(first);
    register(second);

    GraphValidationException e =
        assertThrows(
            GraphValidationException.class,
            () -> GraphActionCollector.collect(actionGraph, ImmutableList.of(firstOutput)));

    assertThat(e.code()).isEqualTo(ACTION_CYCLE);
    assertThat(e).hasMessageThat().contains("cycle");
  }

  private Artifact source(String path) {
    return ActionsTestUtil.createArtifact(sourceRoot, path);
  }

  private Artifact output(String path) {
    return ActionsTestUtil.createArtifact(outputRoot, path);
  }

  private static SpawnAction action(
      ImmutableList<Artifact> inputs, ImmutableList<Artifact> outputs) {
    SpawnAction.Builder builder =
        new SpawnAction.Builder()
            .setExecutable(PathFragment.create("/bin/graph-test"))
            .setMnemonic("GraphTest");
    for (Artifact input : inputs) {
      builder.addInput(input);
    }
    for (Artifact output : outputs) {
      builder.addOutput(output);
    }
    return builder.build(NULL_ACTION_OWNER, /* configuration= */ null);
  }

  private void register(SpawnAction action) {
    for (Artifact output : action.getOutputs()) {
      producers.put(output, action);
    }
  }
}
