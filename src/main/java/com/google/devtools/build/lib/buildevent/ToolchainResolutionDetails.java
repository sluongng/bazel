// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildevent;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;

/** This event is fired when toolchain resolution completes and contains the debugging output. */
@AutoValue
public abstract class ToolchainResolutionDetails implements Postable {
  public static ToolchainResolutionDetails create(
      Label targetPlatform,
      Label executionPlatform,
      ImmutableSetMultimap<ToolchainTypeInfo, Label> resolvedToolchains,
      ImmutableMap<Label, String> rejectedExecutionPlatforms) {
    return new AutoValue_ToolchainResolutionDetails(
        targetPlatform, executionPlatform, resolvedToolchains, rejectedExecutionPlatforms);
  }

  /** The platform that the build rule is being built for. */
  public abstract Label targetPlatform();

  /** The platform that the build actions are being executed on. */
  public abstract Label executionPlatform();

  /** The toolchains that were chosen for this build. */
  public abstract ImmutableSetMultimap<ToolchainTypeInfo, Label> resolvedToolchains();

  /** A map from the labels of rejected execution platforms to the reason they were rejected. */
  public abstract ImmutableMap<Label, String> rejectedExecutionPlatforms();

  /** A builder for {@link ToolchainResolutionDetails}. */
  @AutoValue.Builder
  public interface Builder {
    Builder setTargetPlatform(Label value);

    Builder setExecutionPlatform(Label value);

    Builder setResolvedToolchains(ImmutableSetMultimap<ToolchainTypeInfo, Label> value);

    Builder setRejectedExecutionPlatforms(ImmutableMap<Label, String> value);

    ToolchainResolutionDetails build();
  }

  public static Builder builder() {
    return new AutoValue_ToolchainResolutionDetails.Builder()
        .setResolvedToolchains(ImmutableSetMultimap.of())
        .setRejectedExecutionPlatforms(ImmutableMap.of());
  }

  public abstract Builder toBuilder();

  /**
   * Appends the given rejected execution platforms to the existing map.
   *
   * @param rejectedExecutionPlatforms A map from the labels of rejected execution platforms to the
   *     reason they were rejected.
   * @return A new {@link ToolchainResolutionDetails} with the appended rejected execution
   *     platforms.
   */
  public ToolchainResolutionDetails withRejectedExecutionPlatforms(
      ImmutableMap<Label, String> rejectedExecutionPlatforms) {
    if (rejectedExecutionPlatforms.isEmpty()) {
      return this;
    }
    return toBuilder()
        .setRejectedExecutionPlatforms(
            ImmutableMap.<Label, String>builder()
                .putAll(this.rejectedExecutionPlatforms())
                .putAll(rejectedExecutionPlatforms)
                .buildKeepingLast())
        .build();
  }

  /**
   * Appends the given resolved toolchains to the existing map.
   *
   * @param resolvedToolchains The toolchains that were chosen for this build.
   * @return A new {@link ToolchainResolutionDetails} with the appended resolved toolchains.
   */
  public ToolchainResolutionDetails withResolvedToolchains(
      ImmutableSetMultimap<ToolchainTypeInfo, Label> resolvedToolchains) {
    if (resolvedToolchains.isEmpty()) {
      return this;
    }
    return toBuilder()
        .setResolvedToolchains(
            ImmutableSetMultimap.<ToolchainTypeInfo, Label>builder()
                .putAll(this.resolvedToolchains())
                .putAll(resolvedToolchains)
                .build())
        .build();
  }

  /**
   * Returns a new {@link ToolchainResolutionDetails} with the given execution platform.
   *
   * @param executionPlatform The platform that the build actions are being executed on.
   * @return A new {@link ToolchainResolutionDetails} with the given execution platform.
   */
  public ToolchainResolutionDetails withExecutionPlatform(Label executionPlatform) {
    return toBuilder().setExecutionPlatform(executionPlatform).build();
  }

  /**
   * Returns a new {@link ToolchainResolutionDetails} with the given target platform.
   *
   * @param targetPlatform The platform that the build rule is being built for.
   * @return A new {@link ToolchainResolutionDetails} with the given target platform.
   */
  public ToolchainResolutionDetails withTargetPlatform(Label targetPlatform) {
    return toBuilder().setTargetPlatform(targetPlatform).build();
  }

  /** Returns true if the event has no data. */
  public boolean isEmpty() {
    return targetPlatform() == null
        && executionPlatform() == null
        && resolvedToolchains().isEmpty()
        && rejectedExecutionPlatforms().isEmpty();
  }
}
