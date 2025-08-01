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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ToolchainResolution;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ToolchainResolutionEvent}. */
@RunWith(JUnit4.class)
public class ToolchainResolutionEventTest {

  @Test
  public void testSuccessfulToolchainResolutionEvent() throws Exception {
    // Create mock objects
    ToolchainTypeInfo toolchainType = mock(ToolchainTypeInfo.class);
    when(toolchainType.typeLabel()).thenReturn(Label.parseCanonicalUnchecked("//test:toolchain_type"));
    
    Label toolchainLabel = Label.parseCanonicalUnchecked("//test:toolchain");
    ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainMappings =
        ImmutableSetMultimap.of(toolchainType, toolchainLabel);
    
    BuildOptions buildOptions = mock(BuildOptions.class);
    when(buildOptions.checksum()).thenReturn("test_checksum");
    
    BuildConfigurationKey configKey = mock(BuildConfigurationKey.class);
    when(configKey.toString()).thenReturn("test_config");
    when(configKey.getOptions()).thenReturn(buildOptions);

    ToolchainResolutionEvent event = new ToolchainResolutionEvent(
        "//test:target",
        "//platforms:execution",
        "//platforms:target",
        toolchainMappings,
        configKey,
        true, // success
        null
    );

    // Test event ID generation
    BuildEventId eventId = event.getEventId();
    assertThat(eventId.hasToolchainResolution()).isTrue();
    assertThat(eventId.getToolchainResolution().getTargetLabel()).isEqualTo("//test:target");

    // Test children events (should be empty for leaf events)
    assertThat(event.getChildrenEvents()).isEmpty();

    // Test proto conversion
    BuildEventContext context = mock(BuildEventContext.class);
    BuildEventStreamProtos.BuildEvent streamProto = event.asStreamProto(context);
    
    assertThat(streamProto.hasToolchainResolution()).isTrue();
    ToolchainResolution toolchainResolution = streamProto.getToolchainResolution();
    
    assertThat(toolchainResolution.getTargetLabel()).isEqualTo("//test:target");
    assertThat(toolchainResolution.getExecutionPlatform()).isEqualTo("//platforms:execution");
    assertThat(toolchainResolution.getTargetPlatform()).isEqualTo("//platforms:target");
    assertThat(toolchainResolution.getSuccess()).isTrue();
    assertThat(toolchainResolution.getErrorMessage()).isEmpty();
    
    assertThat(toolchainResolution.getToolchainMappingsCount()).isEqualTo(1);
    ToolchainResolution.ToolchainMapping mapping = toolchainResolution.getToolchainMappings(0);
    assertThat(mapping.getToolchainType()).isEqualTo("//test:toolchain_type");
    assertThat(mapping.getResolvedToolchainsList()).containsExactly("//test:toolchain");
  }

  @Test
  public void testFailedToolchainResolutionEvent() throws Exception {
    BuildOptions buildOptions = mock(BuildOptions.class);
    when(buildOptions.checksum()).thenReturn("test_checksum");
    
    BuildConfigurationKey configKey = mock(BuildConfigurationKey.class);
    when(configKey.toString()).thenReturn("test_config");
    when(configKey.getOptions()).thenReturn(buildOptions);

    ToolchainResolutionEvent event = new ToolchainResolutionEvent(
        "//test:target",
        null, // no execution platform
        null, // no target platform
        ImmutableSetMultimap.of(), // no toolchains
        configKey,
        false, // failure
        "No matching toolchains found"
    );

    // Test proto conversion for failure case
    BuildEventContext context = mock(BuildEventContext.class);
    BuildEventStreamProtos.BuildEvent streamProto = event.asStreamProto(context);
    
    assertThat(streamProto.hasToolchainResolution()).isTrue();
    ToolchainResolution toolchainResolution = streamProto.getToolchainResolution();
    
    assertThat(toolchainResolution.getTargetLabel()).isEqualTo("//test:target");
    assertThat(toolchainResolution.getExecutionPlatform()).isEmpty();
    assertThat(toolchainResolution.getTargetPlatform()).isEmpty();
    assertThat(toolchainResolution.getSuccess()).isFalse();
    assertThat(toolchainResolution.getErrorMessage()).isEqualTo("No matching toolchains found");
    assertThat(toolchainResolution.getToolchainMappingsCount()).isEqualTo(0);
  }
}