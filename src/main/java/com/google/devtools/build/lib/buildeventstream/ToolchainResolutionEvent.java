// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ToolchainResolution;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A {@link BuildEvent} reporting toolchain resolution for a target.
 *
 * <p>Events of this class are generated when toolchains are resolved for a target, providing
 * information about which toolchains were selected, which platforms were used, and whether
 * resolution was successful.
 */
public record ToolchainResolutionEvent(
    String targetLabel,
    @Nullable String executionPlatform,
    @Nullable String targetPlatform,
    ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainMappings,
    BuildConfigurationKey configurationKey,
    boolean success,
    @Nullable String errorMessage)
    implements BuildEvent, ExtendedEventHandler.Postable {

  @Override
  public BuildEventId getEventId() {
    return BuildEventIdUtil.toolchainResolutionId(targetLabel, configurationKey);
  }

  @Override
  public Collection<BuildEventId> getChildrenEvents() {
    return ImmutableList.of();
  }

  @Override
  public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventContext context) {
    ToolchainResolution.Builder builder =
        ToolchainResolution.newBuilder().setTargetLabel(targetLabel).setSuccess(success);

    if (executionPlatform != null) {
      builder.setExecutionPlatform(executionPlatform);
    }

    if (targetPlatform != null) {
      builder.setTargetPlatform(targetPlatform);
    }

    if (errorMessage != null) {
      builder.setErrorMessage(errorMessage);
    }

    // Add toolchain mappings
    for (ToolchainTypeInfo toolchainType : toolchainMappings.keySet()) {
      ToolchainResolution.ToolchainMapping.Builder mappingBuilder =
          ToolchainResolution.ToolchainMapping.newBuilder()
              .setToolchainType(toolchainType.typeLabel().toString());

      for (Label toolchainLabel : toolchainMappings.get(toolchainType)) {
        mappingBuilder.addResolvedToolchains(toolchainLabel.toString());
      }

      builder.addToolchainMappings(mappingBuilder.build());
    }

    return GenericBuildEvent.protoChaining(this).setToolchainResolution(builder.build()).build();
  }
}
