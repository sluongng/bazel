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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.util.AbruptExitException;

/** {@link WorkspaceVersionProvider} that tries delegates in order until one returns data. */
public final class FallbackWorkspaceVersionProvider implements WorkspaceVersionProvider {

  private final ImmutableList<WorkspaceVersionProvider> providers;

  public static WorkspaceVersionProvider of(WorkspaceVersionProvider... providers) {
    return new FallbackWorkspaceVersionProvider(ImmutableList.copyOf(providers));
  }

  private FallbackWorkspaceVersionProvider(ImmutableList<WorkspaceVersionProvider> providers) {
    this.providers = providers;
  }

  @Override
  public WorkspaceVersionInfo getWorkspaceVersion(CommandEnvironment env)
      throws AbruptExitException {
    for (WorkspaceVersionProvider provider : providers) {
      WorkspaceVersionInfo info = provider.getWorkspaceVersion(env);
      if (!isEmpty(info)) {
        return info;
      }
    }
    return WorkspaceVersionInfo.empty();
  }

  private static boolean isEmpty(WorkspaceVersionInfo info) {
    return info.revision().isEmpty()
        && info.evaluatingVersion().isEmpty()
        && info.clientId().isEmpty()
        && !info.hasLocalChanges();
  }
}
