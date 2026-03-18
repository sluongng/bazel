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

package com.google.devtools.build.lib.profiler;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.vfs.Path;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/** Action duration guidance extracted from a Bazel JSON trace profile. */
public final class ProfileGuidance {
  private final ImmutableMap<ActionKey, Long> durationsByOutputLabelAndConfiguration;
  private final ImmutableMap<ActionKey, Long> durationsByOutputAndLabel;
  private final ImmutableMap<ActionKey, Long> durationsByOutputAndConfiguration;
  private final ImmutableMap<String, Long> durationsByOutput;
  private final ImmutableMap<ActionKey, Long> durationsByDescriptionLabelAndConfiguration;
  private final ImmutableMap<String, Long> durationsByDescription;
  private final int actionEventCount;

  private ProfileGuidance(
      Map<ActionKey, Long> durationsByOutputLabelAndConfiguration,
      Map<ActionKey, Long> durationsByOutputAndLabel,
      Map<ActionKey, Long> durationsByOutputAndConfiguration,
      Map<String, Long> durationsByOutput,
      Map<ActionKey, Long> durationsByDescriptionLabelAndConfiguration,
      Map<String, Long> durationsByDescription,
      int actionEventCount) {
    this.durationsByOutputLabelAndConfiguration =
        ImmutableMap.copyOf(durationsByOutputLabelAndConfiguration);
    this.durationsByOutputAndLabel = ImmutableMap.copyOf(durationsByOutputAndLabel);
    this.durationsByOutputAndConfiguration =
        ImmutableMap.copyOf(durationsByOutputAndConfiguration);
    this.durationsByOutput = ImmutableMap.copyOf(durationsByOutput);
    this.durationsByDescriptionLabelAndConfiguration =
        ImmutableMap.copyOf(durationsByDescriptionLabelAndConfiguration);
    this.durationsByDescription = ImmutableMap.copyOf(durationsByDescription);
    this.actionEventCount = actionEventCount;
  }

  public static ProfileGuidance load(Path profilePath) throws IOException {
    InputStream in = profilePath.getInputStream();
    if (profilePath.getBaseName().endsWith(".gz")) {
      in = new GZIPInputStream(in);
    }
    try (JsonReader reader =
        new JsonReader(new BufferedReader(new InputStreamReader(in, UTF_8)))) {
      return parse(reader);
    }
  }

  public long getExpectedDurationNanos(
      String description,
      @Nullable String primaryOutputPath,
      @Nullable String targetLabel,
      @Nullable String configuration) {
    String normalizedDescription = normalize(description);
    String normalizedOutput = normalize(primaryOutputPath);
    String normalizedTargetLabel = normalize(targetLabel);
    String normalizedConfiguration = normalize(configuration);

    if (normalizedOutput != null
        && normalizedTargetLabel != null
        && normalizedConfiguration != null
        && durationsByOutputLabelAndConfiguration.containsKey(
            new ActionKey(
                normalizedOutput, normalizedTargetLabel, normalizedConfiguration))) {
      return durationsByOutputLabelAndConfiguration.get(
          new ActionKey(normalizedOutput, normalizedTargetLabel, normalizedConfiguration));
    }
    if (normalizedOutput != null
        && normalizedTargetLabel != null
        && durationsByOutputAndLabel.containsKey(
            new ActionKey(normalizedOutput, normalizedTargetLabel, null))) {
      return durationsByOutputAndLabel.get(
          new ActionKey(normalizedOutput, normalizedTargetLabel, null));
    }
    if (normalizedOutput != null
        && normalizedConfiguration != null
        && durationsByOutputAndConfiguration.containsKey(
            new ActionKey(normalizedOutput, null, normalizedConfiguration))) {
      return durationsByOutputAndConfiguration.get(
          new ActionKey(normalizedOutput, null, normalizedConfiguration));
    }
    if (normalizedOutput != null && durationsByOutput.containsKey(normalizedOutput)) {
      return durationsByOutput.get(normalizedOutput);
    }
    if (normalizedDescription != null
        && normalizedTargetLabel != null
        && normalizedConfiguration != null
        && durationsByDescriptionLabelAndConfiguration.containsKey(
            new ActionKey(
                normalizedDescription, normalizedTargetLabel, normalizedConfiguration))) {
      return durationsByDescriptionLabelAndConfiguration.get(
          new ActionKey(normalizedDescription, normalizedTargetLabel, normalizedConfiguration));
    }
    if (normalizedDescription != null && durationsByDescription.containsKey(normalizedDescription)) {
      return durationsByDescription.get(normalizedDescription);
    }
    return 0;
  }

  public int getActionEventCount() {
    return actionEventCount;
  }

  private static ProfileGuidance parse(JsonReader reader) throws IOException {
    Builder builder = new Builder();
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case "traceEvents" -> parseTraceEvents(reader, builder);
        default -> reader.skipValue();
      }
    }
    reader.endObject();
    return builder.build();
  }

  private static void parseTraceEvents(JsonReader reader, Builder builder) throws IOException {
    reader.beginArray();
    while (reader.hasNext()) {
      parseTraceEvent(reader, builder);
    }
    reader.endArray();
  }

  private static void parseTraceEvent(JsonReader reader, Builder builder) throws IOException {
    String category = null;
    String description = null;
    String primaryOutputPath = null;
    String targetLabel = null;
    String configuration = null;
    long durationNanos = 0;

    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case "cat" -> category = reader.nextString();
        case "name" -> description = reader.nextString();
        case "dur" -> durationNanos = readDurationNanos(reader);
        case "out" -> primaryOutputPath = reader.nextString();
        case "args" -> {
          reader.beginObject();
          while (reader.hasNext()) {
            switch (reader.nextName()) {
              case "target" -> targetLabel = reader.nextString();
              case "configuration" -> configuration = reader.nextString();
              default -> reader.skipValue();
            }
          }
          reader.endObject();
        }
        default -> reader.skipValue();
      }
    }
    reader.endObject();

    if (ProfilerTask.ACTION.description.equals(category) && durationNanos > 0) {
      builder.addAction(description, primaryOutputPath, targetLabel, configuration, durationNanos);
    }
  }

  private static long readDurationNanos(JsonReader reader) throws IOException {
    return Math.round(reader.nextDouble() * 1000);
  }

  @Nullable
  private static String normalize(@Nullable String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    return value;
  }

  private static <K> void putMax(Map<K, Long> durations, K key, long durationNanos) {
    durations.merge(key, durationNanos, Math::max);
  }

  private record ActionKey(
      String primaryKey, @Nullable String secondaryKey, @Nullable String tertiaryKey) {}

  private static final class Builder {
    private final Map<ActionKey, Long> durationsByOutputLabelAndConfiguration = new HashMap<>();
    private final Map<ActionKey, Long> durationsByOutputAndLabel = new HashMap<>();
    private final Map<ActionKey, Long> durationsByOutputAndConfiguration = new HashMap<>();
    private final Map<String, Long> durationsByOutput = new HashMap<>();
    private final Map<ActionKey, Long> durationsByDescriptionLabelAndConfiguration =
        new HashMap<>();
    private final Map<String, Long> durationsByDescription = new HashMap<>();
    private int actionEventCount;

    private void addAction(
        String description,
        @Nullable String primaryOutputPath,
        @Nullable String targetLabel,
        @Nullable String configuration,
        long durationNanos) {
      actionEventCount++;

      String normalizedDescription = normalize(description);
      String normalizedOutput = normalize(primaryOutputPath);
      String normalizedTargetLabel = normalize(targetLabel);
      String normalizedConfiguration = normalize(configuration);

      if (normalizedOutput != null) {
        putMax(durationsByOutput, normalizedOutput, durationNanos);
        if (normalizedTargetLabel != null) {
          putMax(
              durationsByOutputAndLabel,
              new ActionKey(normalizedOutput, normalizedTargetLabel, null),
              durationNanos);
        }
        if (normalizedConfiguration != null) {
          putMax(
              durationsByOutputAndConfiguration,
              new ActionKey(normalizedOutput, null, normalizedConfiguration),
              durationNanos);
        }
        if (normalizedTargetLabel != null && normalizedConfiguration != null) {
          putMax(
              durationsByOutputLabelAndConfiguration,
              new ActionKey(normalizedOutput, normalizedTargetLabel, normalizedConfiguration),
              durationNanos);
        }
      }

      if (normalizedDescription != null) {
        putMax(durationsByDescription, normalizedDescription, durationNanos);
        if (normalizedTargetLabel != null && normalizedConfiguration != null) {
          putMax(
              durationsByDescriptionLabelAndConfiguration,
              new ActionKey(normalizedDescription, normalizedTargetLabel, normalizedConfiguration),
              durationNanos);
        }
      }
    }

    private ProfileGuidance build() {
      return new ProfileGuidance(
          durationsByOutputLabelAndConfiguration,
          durationsByOutputAndLabel,
          durationsByOutputAndConfiguration,
          durationsByOutput,
          durationsByDescriptionLabelAndConfiguration,
          durationsByDescription,
          actionEventCount);
    }
  }
}
