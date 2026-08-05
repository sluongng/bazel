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
package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES;

import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.common.options.OptionsParsingResult;

/** Builds targets using graph execution. */
@Command(
    name = "gbuild",
    buildPhase = EXECUTES,
    inheritsOptionsFrom = {BuildCommand.class},
    shortDescription = "Builds targets using graph execution.",
    allowResidue = true,
    completion = "label",
    help = "resource:build.txt")
public final class GbuildCommand implements BlazeCommand {
  private final BuildCommand delegate = new BuildCommand();

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    return delegate.exec(env, options);
  }
}
