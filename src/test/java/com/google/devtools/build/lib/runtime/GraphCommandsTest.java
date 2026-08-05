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
package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.BazelBuiltinCommandModule;
import com.google.devtools.build.lib.runtime.commands.BuildCommand;
import com.google.devtools.build.lib.runtime.commands.GbuildCommand;
import com.google.devtools.build.lib.runtime.commands.GtestCommand;
import com.google.devtools.build.lib.runtime.commands.TestCommand;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.common.options.OptionsBase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests registration and option inheritance for graph execution commands. */
@RunWith(JUnit4.class)
public final class GraphCommandsTest {

  @Test
  public void commandsAreRegistered() {
    ServerBuilder builder = new ServerBuilder();
    new BazelBuiltinCommandModule().serverInit(/* startupOptions= */ null, builder);

    assertThat(
            builder.getCommands().stream()
                .map(command -> command.getClass().getAnnotation(Command.class).name()))
        .containsAtLeast("gbuild", "gtest");
  }

  @Test
  public void gbuildInheritsBuildOptions() {
    assertThat(command(GbuildCommand.class).inheritsOptionsFrom())
        .asList()
        .containsExactly(BuildCommand.class);
    assertThat(optionsFor(GbuildCommand.class))
        .containsExactlyElementsIn(optionsFor(BuildCommand.class));
  }

  @Test
  public void gtestInheritsTestOptions() {
    assertThat(command(GtestCommand.class).inheritsOptionsFrom())
        .asList()
        .containsExactly(TestCommand.class);
    assertThat(optionsFor(GtestCommand.class))
        .containsExactlyElementsIn(optionsFor(TestCommand.class));
  }

  @Test
  public void gtestRetainsTestLikeBuildEventOrdering() {
    assertThat(BuildEventStreamer.isTestLikeCommand("gtest")).isTrue();
    assertThat(BuildEventStreamer.isTestLikeCommand("test")).isTrue();
    assertThat(BuildEventStreamer.isTestLikeCommand("gbuild")).isFalse();
  }

  private static Command command(Class<? extends BlazeCommand> commandClass) {
    return commandClass.getAnnotation(Command.class);
  }

  private static ImmutableList<Class<? extends OptionsBase>> optionsFor(
      Class<? extends BlazeCommand> commandClass) {
    ConfiguredRuleClassProvider ruleClassProvider =
        new ConfiguredRuleClassProvider.Builder()
            .setToolsRepository(TestConstants.TOOLS_REPOSITORY)
            .build();
    return BlazeCommandUtils.getOptions(commandClass, ImmutableList.of(), ruleClassProvider);
  }
}
