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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId.GitClientId;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link WorkspaceVersionProvider} implementation that derives workspace metadata from a Git
 * repository.
 *
 * <p>The provider prefers environment variables so that hermetic CI systems can supply data without
 * invoking Git. If they are absent, it falls back to executing {@code git} in the workspace.
 */
public final class GitWorkspaceVersionProvider implements WorkspaceVersionProvider {

  private static final String ENV_GIT_COMMIT = "BAZEL_GIT_COMMIT";
  private static final String ENV_GIT_DIRTY = "BAZEL_GIT_DIRTY";

  public static final GitWorkspaceVersionProvider INSTANCE = new GitWorkspaceVersionProvider();

  private GitWorkspaceVersionProvider() {}

  @Override
  public WorkspaceVersionInfo getWorkspaceVersion(CommandEnvironment env)
      throws AbruptExitException {
    ImmutableMap<String, String> clientEnv = env.getClientEnv();

    Optional<String> revisionFromEnv = readFirstNonEmpty(clientEnv, ENV_GIT_COMMIT, "GIT_COMMIT");
    Boolean dirtyFromEnv = readDirtyFlag(clientEnv);

    Path workspace = chooseWorkspaceRoot(env);
    Optional<String> revision =
        revisionFromEnv.or(() -> gitRevParse(workspace).filter(s -> !s.isEmpty()));
    boolean hasLocalChanges =
        dirtyFromEnv != null ? dirtyFromEnv : revision.isPresent() && gitIsDirty(workspace);

    if (revision.isEmpty() && !hasLocalChanges) {
      return WorkspaceVersionInfo.empty();
    }

    Optional<ClientId> clientId = revision.map(r -> (ClientId) new GitClientId(r, hasLocalChanges));

    return new WorkspaceVersionInfo(
        revision, hasLocalChanges, /* evaluatingVersion= */ Optional.empty(), clientId);
  }

  private static Path chooseWorkspaceRoot(CommandEnvironment env) {
    if (env.getWorkspace() != null) {
      return env.getWorkspace();
    }
    if (env.getDirectories().getWorkspace() != null) {
      return env.getDirectories().getWorkspace();
    }
    return env.getWorkingDirectory();
  }

  @VisibleForTesting
  static Optional<String> readFirstNonEmpty(
      ImmutableMap<String, String> env, String... candidateKeys) {
    for (String key : candidateKeys) {
      String value = env.get(key);
      if (!Strings.isNullOrEmpty(value)) {
        return Optional.of(value.trim());
      }
    }
    return Optional.empty();
  }

  private static Boolean readDirtyFlag(ImmutableMap<String, String> env) {
    String value = env.getOrDefault(ENV_GIT_DIRTY, env.get("GIT_DIRTY"));
    if (Strings.isNullOrEmpty(value)) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.US);
    if (normalized.equals("1") || normalized.equals("true") || normalized.equals("yes")) {
      return true;
    }
    if (normalized.equals("0") || normalized.equals("false") || normalized.equals("no")) {
      return false;
    }
    return null;
  }

  private static Optional<String> gitRevParse(Path workspace) {
    return runGitCommand(workspace, ImmutableList.of("git", "rev-parse", "HEAD"));
  }

  private static boolean gitIsDirty(Path workspace) {
    Optional<String> status =
        runGitCommand(workspace, ImmutableList.of("git", "status", "--porcelain"));
    return status.map(s -> !s.isEmpty()).orElse(false);
  }

  private static Optional<String> runGitCommand(Path workspace, ImmutableList<String> command) {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(workspace.getPathFile());
    processBuilder.redirectErrorStream(true);
    Process process;
    try {
      process = processBuilder.start();
    } catch (IOException e) {
      return Optional.empty();
    }

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (output.length() > 0) {
          output.append('\n');
        }
        output.append(line);
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        return Optional.empty();
      }
      return Optional.of(output.toString().trim());
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
