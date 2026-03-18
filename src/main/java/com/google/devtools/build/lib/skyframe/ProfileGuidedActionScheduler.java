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

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.profiler.ProfileGuidance;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;

/** Computes action priorities from a prior profile. */
final class ProfileGuidedActionScheduler {
  private ProfileGuidedActionScheduler() {}

  static Result compute(ActionLookupValuesTraversal traversal, ProfileGuidance guidance) {
    HashMap<ActionLookupData, Long> priorities = new HashMap<>();
    int matchedActions = 0;
    for (List<ActionLookupValue> shard : traversal.getActionLookupValueShards()) {
      for (ActionLookupValue actionLookupValue : shard) {
        for (var actionMetadata : actionLookupValue.getActions()) {
          if (!(actionMetadata instanceof Action action)) {
            continue;
          }
          Artifact primaryOutput = action.getPrimaryOutput();
          if (!(primaryOutput instanceof Artifact.DerivedArtifact derivedArtifact)) {
            continue;
          }
          long expectedDurationNanos =
              guidance.getExpectedDurationNanos(
                  action.describe(),
                  primaryOutput.getExecPathString(),
                  getOwnerLabel(action.getOwner()),
                  getOwnerConfiguration(action.getOwner()));
          if (expectedDurationNanos <= 0) {
            continue;
          }
          priorities.put(derivedArtifact.getGeneratingActionKey(), expectedDurationNanos);
          matchedActions++;
        }
      }
    }
    return new Result(ImmutableMap.copyOf(priorities), matchedActions);
  }

  private static String getOwnerLabel(@Nullable ActionOwner owner) {
    return owner != null && owner.getLabel() != null ? owner.getLabel().getCanonicalForm() : null;
  }

  private static String getOwnerConfiguration(@Nullable ActionOwner owner) {
    return owner != null ? owner.getConfigurationChecksum() : null;
  }

  record Result(ImmutableMap<ActionLookupData, Long> priorities, int matchedActions) {}
}
