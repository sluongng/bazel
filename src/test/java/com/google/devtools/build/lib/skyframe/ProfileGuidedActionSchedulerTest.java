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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BasicActionLookupValue;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.profiler.ProfileGuidance;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.PathFragment;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ProfileGuidedActionScheduler}. */
@RunWith(JUnit4.class)
public final class ProfileGuidedActionSchedulerTest {
  private final Scratch scratch = new Scratch();

  @Test
  public void compute_matchesActionsByOutputLabelAndConfiguration() throws Exception {
    ActionLookupKey actionLookupKey = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    ArtifactRoot outputRoot =
        ArtifactRoot.asDerivedRoot(scratch.dir("/execroot"), RootType.OUTPUT, "bazel-out");

    DerivedArtifact slowOutput =
        createOutput(outputRoot, "k8-fastbuild/bin/pkg/slow.o", actionLookupKey, 0);
    DerivedArtifact fastOutput =
        createOutput(outputRoot, "k8-fastbuild/bin/pkg/fast.o", actionLookupKey, 1);

    var slowAction =
        new ActionsTestUtil.NullAction(createOwner("//pkg:slow", "cfg123"), slowOutput);
    var fastAction =
        new ActionsTestUtil.NullAction(createOwner("//pkg:fast", "cfg456"), fastOutput);

    ActionLookupValuesTraversal traversal = new ActionLookupValuesTraversal();
    traversal.accumulate(
        actionLookupKey, new BasicActionLookupValue(ImmutableList.of(slowAction, fastAction)));

    var profile =
        scratch.file(
            "profile.json",
            "{",
            "  \"traceEvents\": [",
            actionEventJson(slowAction.describe(), slowOutput, "//pkg:slow", "cfg123", 9000) + ",",
            actionEventJson(fastAction.describe(), fastOutput, "//pkg:fast", "cfg456", 2000),
            "  ]",
            "}");
    ProfileGuidance guidance = ProfileGuidance.load(profile);

    ProfileGuidedActionScheduler.Result result =
        ProfileGuidedActionScheduler.compute(traversal, guidance);

    assertThat(result.matchedActions()).isEqualTo(2);
    assertThat(result.priorities())
        .containsExactly(
            ActionLookupData.create(actionLookupKey, 0), 9_000_000L,
            ActionLookupData.create(actionLookupKey, 1), 2_000_000L);
  }

  private static DerivedArtifact createOutput(
      ArtifactRoot outputRoot,
      String rootRelativePath,
      ActionLookupKey actionLookupKey,
      int actionIndex) {
    DerivedArtifact output =
        (DerivedArtifact)
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create(rootRelativePath));
    output.setGeneratingActionKey(ActionLookupData.create(actionLookupKey, actionIndex));
    return output;
  }

  private static ActionOwner createOwner(String label, String configurationChecksum) {
    return ActionOwner.createDummy(
        Label.parseCanonicalUnchecked(label),
        new Location("ProfileGuidedActionSchedulerTest", 1, 1),
        /* targetKind= */ "dummy-kind",
        /* buildConfigurationMnemonic= */ "k8-fastbuild",
        configurationChecksum,
        new BuildConfigurationEvent(
            BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
            BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
        /* isToolConfiguration= */ false,
        PlatformInfo.EMPTY_PLATFORM_INFO,
        ImmutableList.of(),
        ImmutableMap.of());
  }

  private static String actionEventJson(
      String description,
      DerivedArtifact output,
      String label,
      String configuration,
      long durationMicros) {
    return String.format(
        "    {\"cat\":\"action processing\",\"name\":\"%s\",\"dur\":%d,\"out\":\"%s\","
            + "\"args\":{\"target\":\"%s\",\"configuration\":\"%s\"}}",
        description, durationMicros, output.getExecPathString(), label, configuration);
  }
}
