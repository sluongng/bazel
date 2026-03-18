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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.testutil.Scratch;
import java.io.OutputStreamWriter;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ProfileGuidance}. */
@RunWith(JUnit4.class)
public final class ProfileGuidanceTest {
  private final Scratch scratch = new Scratch();

  @Test
  public void load_extractsActionDurationsAndMetadata() throws Exception {
    var profile =
        scratch.file(
            "profile.json",
            "{",
            "  \"traceEvents\": [",
            "    {",
            "      \"cat\": \"action processing\",",
            "      \"name\": \"Compile //pkg:slow\",",
            "      \"dur\": 4000,",
            "      \"out\": \"bazel-out/k8-fastbuild/bin/pkg/slow.o\",",
            "      \"args\": {",
            "        \"target\": \"//pkg:slow\",",
            "        \"configuration\": \"cfg123\"",
            "      }",
            "    },",
            "    {",
            "      \"cat\": \"action processing\",",
            "      \"name\": \"Link //pkg:slow\",",
            "      \"dur\": 9000,",
            "      \"args\": {",
            "        \"target\": \"//pkg:slow\",",
            "        \"configuration\": \"cfg123\"",
            "      }",
            "    }",
            "  ]",
            "}");

    ProfileGuidance guidance = ProfileGuidance.load(profile);

    assertThat(guidance.getActionEventCount()).isEqualTo(2);
    assertThat(
            guidance.getExpectedDurationNanos(
                "Compile //pkg:slow",
                "bazel-out/k8-fastbuild/bin/pkg/slow.o",
                "//pkg:slow",
                "cfg123"))
        .isEqualTo(4_000_000L);
    assertThat(
            guidance.getExpectedDurationNanos(
                "Link //pkg:slow", null, "//pkg:slow", "cfg123"))
        .isEqualTo(9_000_000L);
  }

  @Test
  public void load_supportsGzipProfiles() throws Exception {
    var profile = scratch.resolve("profile.json.gz");
    profile.getParentDirectory().createDirectoryAndParents();
    try (var gzipOut = new GZIPOutputStream(profile.getOutputStream());
        var writer = new OutputStreamWriter(gzipOut, UTF_8)) {
      writer.write(
          """
          {
            "traceEvents": [
              {
                "cat": "action processing",
                "name": "Compile //pkg:fast",
                "dur": 1500,
                "out": "bazel-out/k8-fastbuild/bin/pkg/fast.o",
                "args": {
                  "target": "//pkg:fast",
                  "configuration": "cfg999"
                }
              }
            ]
          }
          """);
    }

    ProfileGuidance guidance = ProfileGuidance.load(profile);

    assertThat(guidance.getActionEventCount()).isEqualTo(1);
    assertThat(
            guidance.getExpectedDurationNanos(
                "Compile //pkg:fast",
                "bazel-out/k8-fastbuild/bin/pkg/fast.o",
                "//pkg:fast",
                "cfg999"))
        .isEqualTo(1_500_000L);
  }
}
