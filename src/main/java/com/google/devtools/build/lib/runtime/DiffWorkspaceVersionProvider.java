// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.skyframe.IntVersion;
import java.util.Optional;

/**
 * Default {@link WorkspaceVersionProvider} backed by {@link WorkspaceInfoFromDiff}.
 *
 * <p>This implementation mirrors the existing behaviour that relies on Piper-aware diff awareness,
 * and returns empty version information when such integration is unavailable.
 */
public final class DiffWorkspaceVersionProvider implements WorkspaceVersionProvider {

  public static final DiffWorkspaceVersionProvider INSTANCE = new DiffWorkspaceVersionProvider();

  private DiffWorkspaceVersionProvider() {}

  @Override
  public WorkspaceVersionInfo getWorkspaceVersion(CommandEnvironment env)
      throws AbruptExitException {
    WorkspaceInfoFromDiff workspaceInfoFromDiff = env.getWorkspaceInfoFromDiff();
    if (workspaceInfoFromDiff == null) {
      return WorkspaceVersionInfo.empty();
    }

    IntVersion evaluatingVersion = workspaceInfoFromDiff.getEvaluatingVersion();
    Optional<IntVersion> evaluatingVersionOptional =
        evaluatingVersion.getVal() == Long.MIN_VALUE
            ? Optional.empty()
            : Optional.of(evaluatingVersion);

    return new WorkspaceVersionInfo(
        /* revision= */ Optional.empty(),
        /* hasLocalChanges= */ false,
        evaluatingVersionOptional,
        workspaceInfoFromDiff.getSnapshot());
  }
}
