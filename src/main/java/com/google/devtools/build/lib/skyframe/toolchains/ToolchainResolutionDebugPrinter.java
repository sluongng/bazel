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
package com.google.devtools.build.lib.skyframe.toolchains;

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.buildevent.ToolchainResolutionDetails;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.Map;
import javax.annotation.Nullable;

/** A helper interface for printing debug messages from toolchain resolution. */
public sealed interface ToolchainResolutionDebugPrinter {

  static ToolchainResolutionDebugPrinter create(
      boolean debug,
      boolean event,
      ExtendedEventHandler eventHandler,
      @Nullable ToolchainResolutionDetails.Builder detailsBuilder) {
    if (debug) {
      return new EventHandlerImpl(eventHandler);
    }
    if (event) {
      return new BuildEventImpl(eventHandler, detailsBuilder);
    }
    return new NoopPrinter();
  }

  boolean debugEnabled();

  /** Report on which toolchains were selected. */
  void reportSelectedToolchains(
      Label targetPlatform,
      Label executionPlatform,
      ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainTypeToResolved);

  /** Report on an execution platform that was skipped due to constraint mismatches. */
  void reportRemovedExecutionPlatform(
      Label label, ImmutableList<ConstraintValueInfo> missingConstraints);

  void reportRejectedExecutionPlatforms(ImmutableMap<Label, String> rejectedExecutionPlatforms);

  /** A do-nothing implementation for when debug messages are suppressed. */
  final class NoopPrinter implements ToolchainResolutionDebugPrinter {

    private NoopPrinter() {}

    @Override
    public boolean debugEnabled() {
      return false;
    }

    @Override
    public void reportSelectedToolchains(
        Label targetPlatform,
        Label executionPlatform,
        ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainTypeToResolved) {}

    @Override
    public void reportRemovedExecutionPlatform(
        Label label, ImmutableList<ConstraintValueInfo> missingConstraints) {}

    @Override
    public void reportRejectedExecutionPlatforms(
        ImmutableMap<Label, String> rejectedExecutionPlatforms) {}
  }

  /** Implement debug printing using the {@link ExtendedEventHandler}. */
  final class EventHandlerImpl implements ToolchainResolutionDebugPrinter {
    @Override
    public boolean debugEnabled() {
      return true;
    }

    private final ExtendedEventHandler eventHandler;

    private EventHandlerImpl(ExtendedEventHandler eventHandler) {
      this.eventHandler = eventHandler;
    }

    @FormatMethod
    private void debugMessage(@FormatString String template, Object... args) {
      eventHandler.handle(Event.info(String.format(template, args)));
    }

    @Override
    public void reportSelectedToolchains(
        Label targetPlatform,
        Label executionPlatform,
        ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainTypeToResolved) {
      String selectedToolchains =
          toolchainTypeToResolved.entries().stream()
              .map(
                  e ->
                      String.format(
                          "type %s -> toolchain %s", e.getKey().typeLabel(), e.getValue()))
              .collect(joining(", "));
      debugMessage(
          "ToolchainResolution: Target platform %s: Selected execution platform %s," + " %s",
          targetPlatform, executionPlatform, selectedToolchains);
    }

    @Override
    public void reportRemovedExecutionPlatform(
        Label label, ImmutableList<ConstraintValueInfo> missingConstraints) {
      // TODO: jcater - Make this one line listing all constraints.
      for (ConstraintValueInfo constraint : missingConstraints) {
        // The value for this setting is not present in the platform, or doesn't match the
        // expected value.
        debugMessage(
            "ToolchainResolution: Removed execution platform %s from"
                + " available execution platforms, it is missing constraint %s",
            label, constraint.label());
      }
    }

    @Override
    public void reportRejectedExecutionPlatforms(
        ImmutableMap<Label, String> rejectedExecutionPlatforms) {
      if (!rejectedExecutionPlatforms.isEmpty()) {
        for (Map.Entry<Label, String> entry : rejectedExecutionPlatforms.entrySet()) {
          Label toolchainLabel = entry.getKey();
          String message = entry.getValue();
          debugMessage(
              "ToolchainResolution: Rejected execution platform %s; %s", toolchainLabel, message);
        }
      }
    }
  }

  /** Implement debug printing by creating a {@link ToolchainResolutionDetails} build event. */
  final class BuildEventImpl implements ToolchainResolutionDebugPrinter {
    private final ExtendedEventHandler eventHandler;
    private final ToolchainResolutionDetails.Builder details;

    private BuildEventImpl(
        ExtendedEventHandler eventHandler, ToolchainResolutionDetails.Builder details) {
      this.eventHandler = eventHandler;
      this.details = details;
    }

    @Override
    public boolean debugEnabled() {
      return true;
    }

    @Override
    public void reportSelectedToolchains(
        Label targetPlatform,
        Label executionPlatform,
        ImmutableSetMultimap<ToolchainTypeInfo, Label> toolchainTypeToResolved) {
      details.setTargetPlatform(targetPlatform);
      details.setExecutionPlatform(executionPlatform);
      details.setResolvedToolchains(toolchainTypeToResolved);
      eventHandler.post(details.build());
    }

    @Override
    public void reportRemovedExecutionPlatform(
        Label label, ImmutableList<ConstraintValueInfo> missingConstraints) {
      // This is not reported in the build event.
    }

    @Override
    public void reportRejectedExecutionPlatforms(
        ImmutableMap<Label, String> rejectedExecutionPlatforms) {
      details.setRejectedExecutionPlatforms(rejectedExecutionPlatforms);
    }
  }
}
