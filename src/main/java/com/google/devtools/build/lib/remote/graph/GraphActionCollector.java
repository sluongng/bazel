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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects the action closure needed to graph-execute a set of top-level artifacts.
 *
 * <p>This class deliberately stops before spawn lowering. Each {@link CollectedAction} retains the
 * validated {@link SpawnAction} so callers can lower it with the execution-time input metadata and
 * client environment.
 *
 * <p>The initial supported subset is intentionally strict: static, single-spawn {@link
 * SpawnAction}s over ordinary file artifacts. Unsupported action or artifact semantics fail closed
 * instead of silently producing an incomplete graph.
 */
public final class GraphActionCollector {
  private static final Comparator<Artifact> ARTIFACT_ORDER =
      Comparator.comparing(Artifact::getExecPathString);

  private final ActionGraph actionGraph;
  private final IdentityHashMap<SpawnAction, NodeBuilder> nodes = new IdentityHashMap<>();
  private final Map<String, SpawnAction> actionsByNodeId = new HashMap<>();
  private final Map<String, SpawnAction> producersByOutputPath = new HashMap<>();

  private GraphActionCollector(ActionGraph actionGraph) {
    this.actionGraph = actionGraph;
  }

  /** Collects and validates the complete action closure for {@code topLevelArtifacts}. */
  public static CollectedGraph collect(
      ActionGraph actionGraph, Iterable<? extends Artifact> topLevelArtifacts)
      throws GraphValidationException {
    return new GraphActionCollector(actionGraph).collect(topLevelArtifacts);
  }

  private CollectedGraph collect(Iterable<? extends Artifact> topLevelArtifacts)
      throws GraphValidationException {
    TreeMap<String, Artifact> rootsByPath = new TreeMap<>();
    for (Artifact root : topLevelArtifacts) {
      // The executor lifecycle's top-level artifact set includes workspace status outputs even
      // though they are execution metadata rather than requested graph roots.
      if (isWorkspaceStatusArtifact(root)) {
        continue;
      }
      validateArtifact(root);
      Artifact previous = rootsByPath.putIfAbsent(root.getExecPathString(), root);
      if (previous != null && !previous.equals(root)) {
        throw error(
            ValidationCode.DUPLICATE_ROOT_PATH,
            "top-level artifacts have the same exec path: " + root.getExecPathString());
      }
    }
    if (rootsByPath.isEmpty()) {
      throw error(ValidationCode.NO_ROOTS, "graph execution requires at least one root artifact");
    }

    ImmutableList.Builder<Root> roots = ImmutableList.builder();
    for (Artifact root : rootsByPath.values()) {
      if (root.isSourceArtifact()) {
        throw error(
            ValidationCode.SOURCE_ROOT,
            "top-level source artifact has no graph action: " + root.getExecPathString());
      }
      SpawnAction producer = requireProducer(root);
      NodeBuilder node = addAction(producer);
      roots.add(new Root(root, node.nodeId, root.getExecPathString()));
    }

    return new CollectedGraph(roots.build(), topologicalActions());
  }

  private NodeBuilder addAction(SpawnAction action) throws GraphValidationException {
    NodeBuilder existing = nodes.get(action);
    if (existing != null) {
      return existing;
    }
    validateAction(action);

    ImmutableList<Artifact> outputs = sortedArtifacts(action.getOutputs());
    String nodeId = nodeId(outputs);
    SpawnAction nodeIdOwner = actionsByNodeId.putIfAbsent(nodeId, action);
    if (nodeIdOwner != null && nodeIdOwner != action) {
      throw error(
          ValidationCode.NODE_ID_COLLISION,
          "distinct actions map to deterministic node ID " + nodeId);
    }

    String previousOutputPath = null;
    for (Artifact output : outputs) {
      validateArtifact(output);
      if (output.getExecPathString().equals(previousOutputPath)) {
        throw error(
            ValidationCode.DUPLICATE_OUTPUT_PATH,
            describe(action)
                + " declares multiple outputs at exec path "
                + output.getExecPathString());
      }
      previousOutputPath = output.getExecPathString();
      if (output.isSourceArtifact()) {
        throw error(
            ValidationCode.SOURCE_OUTPUT,
            describe(action) + " declares a source output: " + output.getExecPathString());
      }
      SpawnAction oldProducer =
          producersByOutputPath.putIfAbsent(output.getExecPathString(), action);
      if (oldProducer != null && oldProducer != action) {
        throw error(
            ValidationCode.DUPLICATE_OUTPUT_PATH,
            "multiple actions produce " + output.getExecPathString());
      }
    }

    NodeBuilder node = new NodeBuilder(nodeId, action, outputs);
    // Add before descending so cycles terminate and are diagnosed by the topological pass.
    nodes.put(action, node);

    TreeMap<String, Artifact> inputsByPath = uniqueArtifacts(action.getInputs().toList(), action);
    for (Artifact input : inputsByPath.values()) {
      validateArtifact(input);
      if (isEagerFileInput(input)) {
        node.inputs.add(new SourceInput(input));
      } else {
        SpawnAction producer = requireProducer(input);
        NodeBuilder producerNode = addAction(producer);
        node.dependencies.add(producerNode.nodeId);
        node.inputs.add(new ProducedInput(input, producerNode.nodeId, input.getExecPathString()));
      }
    }

    TreeMap<String, Artifact> schedulingDependenciesByPath =
        uniqueArtifacts(action.getSchedulingDependencies().toList(), action);
    for (Artifact dependency : schedulingDependenciesByPath.values()) {
      validateArtifact(dependency);
      if (!isEagerFileInput(dependency)) {
        SpawnAction producer = requireProducer(dependency);
        NodeBuilder producerNode = addAction(producer);
        node.dependencies.add(producerNode.nodeId);
        node.schedulingDependencies.add(producerNode.nodeId);
      }
    }
    return node;
  }

  private boolean isEagerFileInput(Artifact artifact) throws GraphValidationException {
    if (artifact.isSourceArtifact() || artifact.isConstantMetadata()) {
      return true;
    }
    return isWorkspaceStatusArtifact(artifact);
  }

  private boolean isWorkspaceStatusArtifact(Artifact artifact) throws GraphValidationException {
    ActionAnalysisMetadata producer;
    try {
      producer = actionGraph.getGeneratingAction(artifact);
    } catch (RuntimeException e) {
      throw new GraphValidationException(
          ValidationCode.ACTION_LOOKUP_FAILED,
          "failed to look up the producer of " + artifact.getExecPathString(),
          e);
    }
    return producer instanceof WorkspaceStatusAction;
  }

  private SpawnAction requireProducer(Artifact artifact) throws GraphValidationException {
    ActionAnalysisMetadata producer;
    try {
      producer = actionGraph.getGeneratingAction(artifact);
    } catch (RuntimeException e) {
      throw new GraphValidationException(
          ValidationCode.ACTION_LOOKUP_FAILED,
          "failed to look up the producer of " + artifact.getExecPathString(),
          e);
    }
    if (producer == null) {
      throw error(
          ValidationCode.MISSING_PRODUCER,
          "derived artifact has no known producer: " + artifact.getExecPathString());
    }
    if (!(producer instanceof SpawnAction spawnAction)) {
      throw error(
          ValidationCode.UNSUPPORTED_ACTION,
          "producer of "
              + artifact.getExecPathString()
              + " is not a SpawnAction: "
              + producer.getClass().getName());
    }
    if (!producer.getOutputs().contains(artifact)) {
      throw error(
          ValidationCode.PRODUCER_OUTPUT_MISMATCH,
          describe(producer)
              + " does not declare looked-up artifact "
              + artifact.getExecPathString()
              + " as an output");
    }
    return spawnAction;
  }

  private static void validateAction(SpawnAction action) throws GraphValidationException {
    if (action.discoversInputs() || !action.inputsKnown()) {
      throw error(
          ValidationCode.DYNAMIC_INPUTS,
          describe(action) + " uses input discovery, which graph execution does not yet support");
    }
    if (action.mayInsensitivelyPropagateInputs()) {
      throw error(
          ValidationCode.INSENSITIVE_INPUT_PROPAGATION,
          describe(action) + " propagates input contents without consuming them");
    }
    if (action.isAggregator()) {
      throw error(
          ValidationCode.AGGREGATING_ACTION,
          describe(action) + " aggregates inputs instead of producing ordinary files");
    }
    if (action.mayModifySpawnOutputsAfterExecution()) {
      throw error(
          ValidationCode.POSTPROCESSED_OUTPUTS,
          describe(action) + " may modify outputs after its spawn finishes");
    }
    if (action.getOutputs().isEmpty()) {
      throw error(ValidationCode.NO_OUTPUTS, describe(action) + " has no outputs");
    }
  }

  private static void validateArtifact(Artifact artifact) throws GraphValidationException {
    PathFragment execPath = artifact.getExecPath();
    if (execPath.isEmpty() || execPath.isAbsolute() || execPath.containsUplevelReferences()) {
      throw error(
          ValidationCode.INVALID_EXEC_PATH,
          "artifact has a non-normalized relative exec path: " + execPath.getPathString());
    }
    if (artifact.isDirectory()
        || artifact.isRunfilesTree()
        || artifact.isFileset()
        || artifact.isSymlink()
        || artifact instanceof TreeFileArtifact
        || artifact.isChildOfDeclaredDirectory()) {
      throw error(
          ValidationCode.UNSUPPORTED_ARTIFACT,
          "graph execution supports only ordinary file artifacts: " + execPath.getPathString());
    }
  }

  private static TreeMap<String, Artifact> uniqueArtifacts(
      Collection<Artifact> artifacts, SpawnAction action) throws GraphValidationException {
    TreeMap<String, Artifact> byPath = new TreeMap<>();
    for (Artifact artifact : artifacts) {
      Artifact previous = byPath.putIfAbsent(artifact.getExecPathString(), artifact);
      if (previous != null && !previous.equals(artifact)) {
        throw error(
            ValidationCode.DUPLICATE_INPUT_PATH,
            describe(action)
                + " has distinct dependencies at exec path "
                + artifact.getExecPathString());
      }
    }
    return byPath;
  }

  private ImmutableList<CollectedAction> topologicalActions() throws GraphValidationException {
    Map<String, NodeBuilder> byId = new HashMap<>();
    Map<String, Integer> remainingDependencies = new HashMap<>();
    Multimap<String, String> dependents = HashMultimap.create();
    for (NodeBuilder node : nodes.values()) {
      byId.put(node.nodeId, node);
      remainingDependencies.put(node.nodeId, node.dependencies.size());
      for (String dependency : node.dependencies) {
        dependents.put(dependency, node.nodeId);
      }
    }

    PriorityQueue<String> ready = new PriorityQueue<>();
    for (Map.Entry<String, Integer> entry : remainingDependencies.entrySet()) {
      if (entry.getValue() == 0) {
        ready.add(entry.getKey());
      }
    }

    ImmutableList.Builder<CollectedAction> result = ImmutableList.builder();
    int emitted = 0;
    while (!ready.isEmpty()) {
      String nodeId = ready.remove();
      NodeBuilder node = byId.get(nodeId);
      result.add(node.build());
      emitted++;
      for (String dependent : dependents.get(nodeId)) {
        int remaining = remainingDependencies.compute(dependent, (unused, count) -> count - 1);
        if (remaining == 0) {
          ready.add(dependent);
        }
      }
    }
    if (emitted != nodes.size()) {
      throw error(
          ValidationCode.ACTION_CYCLE,
          "action graph contains a cycle involving node IDs "
              + cyclicNodeIds(remainingDependencies));
    }
    return result.build();
  }

  private static ImmutableList<String> cyclicNodeIds(Map<String, Integer> remainingDependencies) {
    return remainingDependencies.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(Map.Entry::getKey)
        .sorted()
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableList<Artifact> sortedArtifacts(Collection<Artifact> artifacts) {
    return artifacts.stream().sorted(ARTIFACT_ORDER).collect(ImmutableList.toImmutableList());
  }

  @SuppressWarnings(
      "deprecation") // The stable SHA-256 identifier is intentionally non-cryptographic.
  private static String nodeId(ImmutableList<Artifact> outputs) {
    StringBuilder identity = new StringBuilder();
    for (Artifact output : outputs) {
      identity.append(output.getExecPathString().length());
      identity.append(':');
      identity.append(output.getExecPathString());
      identity.append(';');
    }
    return "action-" + Hashing.sha256().hashString(identity, UTF_8);
  }

  private static String describe(ActionAnalysisMetadata action) {
    return action.getMnemonic()
        + " action producing "
        + action.getPrimaryOutput().getExecPathString();
  }

  private static GraphValidationException error(ValidationCode code, String message) {
    return new GraphValidationException(code, message);
  }

  /** A validated graph in deterministic dependency-before-consumer order. */
  public record CollectedGraph(ImmutableList<Root> roots, ImmutableList<CollectedAction> actions) {}

  /** A requested top-level artifact and its producing graph node. */
  public record Root(Artifact artifact, String producerNodeId, String outputPath) {}

  /** One validated action declaration, retaining the action for execution-time spawn lowering. */
  public record CollectedAction(
      String nodeId,
      SpawnAction action,
      ImmutableList<InputBinding> inputs,
      ImmutableList<String> schedulingDependencies,
      ImmutableList<Artifact> outputs) {}

  /** A regular action input, classified by how graph execution makes it available. */
  public sealed interface InputBinding permits SourceInput, ProducedInput {
    Artifact artifact();

    default String execPath() {
      return artifact().getExecPathString();
    }
  }

  /** A source input whose digest must be supplied by the source metadata/CAS layer. */
  public record SourceInput(Artifact artifact) implements InputBinding {}

  /** A generated input bound to one declared output of another graph node. */
  public record ProducedInput(Artifact artifact, String producerNodeId, String outputPath)
      implements InputBinding {}

  /** Stable failure categories for the intentionally strict initial subset. */
  public enum ValidationCode {
    NO_ROOTS,
    DUPLICATE_ROOT_PATH,
    SOURCE_ROOT,
    ACTION_LOOKUP_FAILED,
    MISSING_PRODUCER,
    PRODUCER_OUTPUT_MISMATCH,
    UNSUPPORTED_ACTION,
    DYNAMIC_INPUTS,
    INSENSITIVE_INPUT_PROPAGATION,
    AGGREGATING_ACTION,
    POSTPROCESSED_OUTPUTS,
    NO_OUTPUTS,
    SOURCE_OUTPUT,
    INVALID_EXEC_PATH,
    UNSUPPORTED_ARTIFACT,
    DUPLICATE_INPUT_PATH,
    DUPLICATE_OUTPUT_PATH,
    NODE_ID_COLLISION,
    ACTION_CYCLE
  }

  /** A fail-closed validation error with a machine-testable reason. */
  public static final class GraphValidationException extends Exception {
    private final ValidationCode code;

    private GraphValidationException(ValidationCode code, String message) {
      super(message);
      this.code = code;
    }

    private GraphValidationException(ValidationCode code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    public ValidationCode code() {
      return code;
    }
  }

  private static final class NodeBuilder {
    private final String nodeId;
    private final SpawnAction action;
    private final ImmutableList<Artifact> outputs;
    private final List<InputBinding> inputs = new ArrayList<>();
    private final Set<String> schedulingDependencies = new TreeSet<>();
    private final Set<String> dependencies = new TreeSet<>();

    private NodeBuilder(String nodeId, SpawnAction action, ImmutableList<Artifact> outputs) {
      this.nodeId = nodeId;
      this.action = action;
      this.outputs = outputs;
    }

    private CollectedAction build() {
      return new CollectedAction(
          nodeId,
          action,
          ImmutableList.copyOf(inputs),
          ImmutableList.copyOf(schedulingDependencies),
          outputs);
    }
  }
}
