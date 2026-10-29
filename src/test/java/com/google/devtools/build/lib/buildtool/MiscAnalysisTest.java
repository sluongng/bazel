// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheDeps;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingDependenciesProvider;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.io.OutErr;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Miscellaneous tests of the analysis phase. (Sometimes it's easier to express these in terms of
 * the BuildTool than of the BuildView because the latter's class interface is quite complex.)
 */
@RunWith(JUnit4.class)
public class MiscAnalysisTest extends BuildIntegrationTestCase {

  private boolean testModuleHasBackgroundReaders;

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(
            new BlazeModule() {
              @Override
              public boolean mayReadAnalysisAfterCommand() {
                return testModuleHasBackgroundReaders;
              }
            });
  }

  private void writeHibernationFixture(String text) throws Exception {
    write(
        "hib/defs.bzl",
        """
        Payload = provider(fields = ["text"])
        Empty = provider(fields = [])
        Many = provider(fields = ["a", "b", "c", "d", "e", "f"])
        def _impl(ctx):
            return [Payload(text = ctx.attr.text * 2000), Empty(),
                    Many(a = (ctx.attr.text, "tuple"), b = None, c = None,
                         d = None, e = None, f = None)]
        payload_rule = rule(implementation = _impl, attrs = {"text": attr.string()})
        """);
    write(
        "hib/BUILD",
        """
        load(":defs.bzl", "payload_rule")
        payload_rule(name = "dep", text = "%s")
        alias(name = "target", actual = ":dep")
        """
            .formatted(text));
    addOptions("--nobuild", "--experimental_idle_analysis_cache");
  }

  private void hibernateAnalysis() throws Exception {
    var task = getSkyframeExecutor().createAnalysisHibernationTask(Duration.ZERO, 16 * 1024 * 1024);
    assertThat(task).isNotNull();
    task.run();
    assertThat(getSkyframeExecutor().isAnalysisHibernated()).isTrue();
  }

  private static TransitiveInfoProviderMapImpl providerMap(ConfiguredTarget target) {
    return (TransitiveInfoProviderMapImpl) ((RuleConfiguredTarget) target).getProvidersForMetrics();
  }

  private static StarlarkInfo providerInfo(TransitiveInfoProviderMapImpl map, String name) {
    for (int i = 0; i < map.getProviderCount(); i++) {
      if (map.getProviderInstanceAt(i) instanceof StarlarkInfo info
          && info.getProvider().getPrintableName().equals(name)) {
        return info;
      }
    }
    throw new AssertionError("provider not found: " + name);
  }

  private static String payloadText(ConfiguredTarget target) {
    return (String) providerInfo(providerMap(target), "Payload").getValue("text");
  }

  @Test
  public void idleAnalysisSnapshot_restoresUnchangedTargetAndAliasWithoutReanalysis()
      throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    ConfiguredTarget original = getConfiguredTarget("//hib:dep");
    var map = providerMap(original);
    hibernateAnalysis();
    assertThat(map.isHibernated()).isTrue();
    buildTarget("//hib:target");
    assertThat(getConfiguredTarget("//hib:dep")).isSameInstanceAs(original);
    assertThat(providerMap(original)).isSameInstanceAs(map);
    assertThat(payloadText(original)).isEqualTo("first".repeat(2000));
    assertThat(providerInfo(map, "Empty").getFieldNames()).isEmpty();
    var many = providerInfo(map, "Many");
    assertThat(many.getFieldNames()).hasSize(6);
    assertThat(many.getValue("a").toString()).contains("first");
    // A second complete cycle must remain usable.
    hibernateAnalysis();
    buildTarget("//hib:target");
    assertThat(getConfiguredTarget("//hib:dep")).isSameInstanceAs(original);
  }

  @Test
  public void idleAnalysisSnapshot_interruptedRuntimeRestoreKeepsGraphForRetry() throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    ConfiguredTarget original = getConfiguredTarget("//hib:dep");
    var map = providerMap(original);
    hibernateAnalysis();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () -> getRuntime().prepareAnalysisForCommand("build", /* outErr= */ null));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(getSkyframeExecutor().isAnalysisHibernated()).isTrue();
      assertThat(map.isHibernated()).isTrue();
    } finally {
      Thread.interrupted();
    }
    getRuntime().prepareAnalysisForCommand("build", /* outErr= */ null);
    assertThat(getSkyframeExecutor().isAnalysisHibernated()).isFalse();
    assertThat(map.isHibernated()).isFalse();
    assertThat(payloadText(original)).isEqualTo("first".repeat(2000));
    buildTarget("//hib:target");
    assertThat(getConfiguredTarget("//hib:dep")).isSameInstanceAs(original);
  }

  @Test
  public void idleAnalysisSnapshot_restoresAspectMapSharedWithAlias() throws Exception {
    writeHibernationFixture("first");
    write(
        "hib/extra.bzl",
        """
        AspectPayload = provider(fields = ["text"])
        def _impl(target, ctx):
            return [AspectPayload(text = str(target.label) * 2000)]
        extra = aspect(implementation = _impl)
        """);
    addOptions("--aspects=//hib:extra.bzl%extra");
    buildTarget("//hib:dep", "//hib:target");
    var aspects = ImmutableList.copyOf(getBuildResultListener().getAnalyzedAspects().values());
    assertThat(aspects).hasSize(2);
    var map = (TransitiveInfoProviderMapImpl) aspects.get(0).getProviders();
    assertThat(aspects.get(1).getProviders()).isSameInstanceAs(map);
    StarlarkInfo original = providerInfo(map, "AspectPayload");
    Object text = original.getValue("text");
    hibernateAnalysis();
    assertThat(map.isHibernated()).isTrue();
    buildTarget("//hib:dep", "//hib:target");
    var restoredAspects = getBuildResultListener().getAnalyzedAspects().values();
    assertThat(restoredAspects).hasSize(2);
    for (var aspect : restoredAspects) {
      assertThat(aspect.getProviders()).isSameInstanceAs(map);
    }
    StarlarkInfo restored = providerInfo(map, "AspectPayload");
    assertThat(restored).isNotSameInstanceAs(original);
    assertThat(restored.getProvider()).isSameInstanceAs(original.getProvider());
    assertThat(restored.getValue("text")).isEqualTo(text);
  }

  @Test
  public void idleAnalysisSnapshot_runtimeSchedulesConfiguredTaskAndHonorsSizeAndDisableGates()
      throws Exception {
    writeHibernationFixture("x".repeat(600));
    write(
        "hib/defs.bzl",
        """
        Payload = provider(fields = ["text"])
        def _impl(ctx):
            return [Payload(text = [ctx.attr.text + str(i) for i in range(1000)])]
        payload_rule = rule(implementation = _impl, attrs = {"text": attr.string()})
        """);
    addOptions(
        "--experimental_idle_analysis_cache_delay=2s",
        "--experimental_idle_analysis_cache_max_size_mb=1");
    buildTarget("//hib:target");
    var scheduled =
        getRuntimeWrapper().getLastCommandResult().getIdleTasks().stream()
            .filter(task -> task.displayName().equals("Analysis provider hibernation"))
            .findFirst()
            .orElseThrow();
    assertThat(scheduled.delay()).isEqualTo(Duration.ofSeconds(2));
    scheduled.run(); // About 600 KiB fits a correctly converted 1 MiB cap, not a 1-byte cap.
    assertThat(getSkyframeExecutor().isAnalysisHibernated()).isTrue();
    write(
        "hib/BUILD",
        """
        load(":defs.bzl", "payload_rule")
        payload_rule(name = "dep", text = "%s")
        alias(name = "target", actual = ":dep")
        """
            .formatted("x".repeat(2000)));
    buildTarget("//hib:target");
    var tooLarge =
        getRuntimeWrapper().getLastCommandResult().getIdleTasks().stream()
            .filter(task -> task.displayName().equals("Analysis provider hibernation"))
            .findFirst()
            .orElseThrow();
    assertThrows(com.google.devtools.build.lib.server.IdleTaskException.class, tooLarge::run);
    assertThat(getSkyframeExecutor().isAnalysisHibernated()).isFalse();
    addOptions("--noexperimental_idle_analysis_cache");
    buildTarget("//hib:target");
    assertThat(
            getRuntimeWrapper().getLastCommandResult().getIdleTasks().stream()
                .anyMatch(task -> task.displayName().equals("Analysis provider hibernation")))
        .isFalse();
    addOptions("--experimental_idle_analysis_cache", "--nokeep_state_after_build");
    buildTarget("//hib:target");
    assertThat(
            getRuntimeWrapper().getLastCommandResult().getIdleTasks().stream()
                .anyMatch(task -> task.displayName().equals("Analysis provider hibernation")))
        .isFalse();
  }

  @Test
  public void idleAnalysisSnapshot_normalInvalidationSeesBuildAndBzlEdits() throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    hibernateAnalysis();
    write(
        "hib/BUILD",
        """
        load(":defs.bzl", "payload_rule")
        payload_rule(name = "dep", text = "changed")
        alias(name = "target", actual = ":dep")
        """);
    buildTarget("//hib:target");
    assertThat(payloadText(getConfiguredTarget("//hib:dep"))).isEqualTo("changed".repeat(2000));
    hibernateAnalysis();
    write(
        "hib/defs.bzl",
        """
        Payload = provider(fields = ["text"])
        def _impl(ctx):
            return [Payload(text = ctx.attr.text + "-bzl")]
        payload_rule = rule(implementation = _impl, attrs = {"text": attr.string()})
        """);
    buildTarget("//hib:target");
    assertThat(payloadText(getConfiguredTarget("//hib:dep"))).isEqualTo("changed-bzl");
  }

  @Test
  public void idleAnalysisSnapshot_corruptionColdResetsBeforeNextCommand() throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    ConfiguredTarget original = getConfiguredTarget("//hib:dep");
    hibernateAnalysis();
    try (var files =
        Files.walk(Path.of(outputBase.getPathString(), "server", "provider-hibernation"))) {
      Path file =
          files.filter(path -> path.toString().endsWith(".committed")).findFirst().orElseThrow();
      Files.write(file, new byte[] {1});
    }
    var stderr = new ByteArrayOutputStream();
    getRuntime()
        .prepareAnalysisForCommand("build", OutErr.create(new ByteArrayOutputStream(), stderr));
    assertThat(stderr.toString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("discarded the in-memory build graph and will recompute it");
    buildTarget("//hib:target");
    assertThat(getConfiguredTarget("//hib:dep")).isNotSameInstanceAs(original);
    assertThat(payloadText(getConfiguredTarget("//hib:dep"))).isEqualTo("first".repeat(2000));
    assertThat(providerMap(original).toString()).contains("abandoned");
  }

  @Test
  public void idleAnalysisSnapshot_backgroundReaderExclusionSurvivesReset() throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    getSkyframeExecutor().disableAnalysisHibernationForBackgroundReaders();
    assertThat(getSkyframeExecutor().createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024))
        .isNull();
    getSkyframeExecutor().resetEvaluator();
    buildTarget("//hib:target");
    assertThat(getSkyframeExecutor().createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024))
        .isNull();
  }

  @Test
  public void idleAnalysisSnapshot_moduleReaderHookDisablesScheduling() throws Exception {
    writeHibernationFixture("first");
    testModuleHasBackgroundReaders = true;
    buildTarget("//hib:target");
    assertThat(getSkyframeExecutor().createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024))
        .isNull();
    testModuleHasBackgroundReaders = false;
    buildTarget("//hib:target");
    assertThat(getSkyframeExecutor().createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024))
        .isNull();
  }

  @Test
  public void idleAnalysisSnapshot_uploadOnlySkycacheExclusionIsSticky() throws Exception {
    writeHibernationFixture("first");
    buildTarget("//hib:target");
    var executor = getSkyframeExecutor();
    var original = executor.getRemoteAnalysisCachingDependenciesProvider();
    var provider = mock(RemoteAnalysisCachingDependenciesProvider.class);
    when(provider.mode()).thenReturn(RemoteAnalysisCacheMode.UPLOAD);
    executor.setRemoteAnalysisCachingDependenciesProvider(
        provider, RemoteAnalysisCacheDeps.createDisabled());
    assertThat(executor.createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024)).isNull();
    executor.setRemoteAnalysisCachingDependenciesProvider(
        original, RemoteAnalysisCacheDeps.createDisabled());
    executor.resetEvaluator();
    buildTarget("//hib:target");
    assertThat(executor.createAnalysisHibernationTask(Duration.ZERO, 1024 * 1024)).isNull();
  }

  @Test
  public void testWarningsNotReplayed() throws Exception {
    AnalysisMock.get().pySupport().setup(mockToolsConfig);
    write(
        "y/BUILD",
        "genrule(name='y', outs=['y.out'], cmd='touch $@', deprecation='generate a warning')");
    addOptions("--nobuild");

    buildTarget("//y");
    events.assertContainsWarning("target '//y:y' is deprecated");

    buildTarget("//y");
    assertDoesNotContainEvent("target '//y:y' is deprecated");
  }

  @Test
  public void testDeprecatedTargetOnCommandLine() throws Exception {
    write(
        "raspberry/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name='raspberry', srcs=['raspberry.sh'], deprecation='rotten')");
    addOptions("--nobuild");
    buildTarget("//raspberry:raspberry");
    events.assertContainsWarning("target '//raspberry:raspberry' is deprecated: rotten");
  }

  @Test
  public void targetAnalyzedInTwoConfigurations_deprecationWarningDisplayedOncePerBuild()
      throws Exception {
    // :a depends on :dep in the target configuration. :b depends on :dep in the exec configuration.
    write(
        "foo/BUILD",
        """
        genrule(
            name = "a",
            srcs = [":dep"],
            outs = ["a.out"],
            cmd = "touch $@",
        )

        genrule(
            name = "b",
            outs = ["b.out"],
            cmd = "touch $@",
            tools = [":dep"],
        )

        genrule(
            name = "dep",
            srcs = ["//deprecated"],
            outs = ["dep.out"],
            cmd = "touch $@",
        )
        """);
    write(
        "deprecated/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'deprecated', deprecation = 'old')");
    addOptions("--nobuild");
    buildTarget("//foo:a", "//foo:b");
    events.assertContainsEventWithFrequency(
        "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1);

    // Edit to force re-analysis.
    write(
        "deprecated/BUILD",
        "load('//test_defs:foo_library.bzl', 'foo_library')",
        "foo_library(name = 'deprecated', deprecation = 'very old')");
    buildTarget("//foo:a", "//foo:b");
    events.assertContainsEventWithFrequency(
        "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1);
  }

  // Regression test for http://b/12465751: "IllegalStateException in ParallelEvaluator".
  @Test
  public void testShBinaryTwoSrcs() throws Exception {
    write(
        "test_defs/foo_one.bzl",
        """
        def _impl(ctx):
          if len(ctx.files.srcs) != 1:
             fail("you must specify exactly one file in 'srcs'", attr = "srcs")
        foo_one = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
          },
        )
        """);
    write(
        "sh/BUILD",
        "load('//test_defs:foo_one.bzl', 'foo_one')",
        "foo_one(name = 'double', srcs = ['a','b'])");
    addOptions("--nobuild");

    assertThrows(Exception.class, () -> buildTarget("//sh:double"));
    events.assertContainsError("you must specify exactly one file in 'srcs'");
  }

  // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
  @Test
  public void testAnalysisCachingAndKeepGoing() throws Exception {
    write(
        "fruit/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "apple",
            deps = [":banana"],
        )

        cc_library(
            name = "banana",
            deps = [":cherry"],
        )

        cc_library(
            name = "cherry",
            deps = [":durian__hdrs__"],
        )

        genrule(
            name = "durian",
            outs = ["durian.out"],
            cmd = ":",
        )
        """);
    addOptions("--nobuild", "--keep_going");

    BuildFailedException e =
        assertThrows(BuildFailedException.class, () -> buildTarget("//fruit:apple"));
    assertThat(e).hasMessageThat().contains("command succeeded");
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:cherry: "
            + "target '//fruit:durian__hdrs__' does not exist");

    e = assertThrows(BuildFailedException.class, () -> buildTarget("//fruit:apple"));
    assertThat(e).hasMessageThat().contains("command succeeded");
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:cherry: "
            + "target '//fruit:durian__hdrs__' does not exist");
  }

  // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
  @Test
  public void testErrorsAreReplayedEvenWithAnalysisCaching() throws Exception {
    write(
        "fruit/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "apple",
            deps = [":banana__hdrs__"],
        )

        genrule(
            name = "banana",
            outs = ["banana.out"],
            cmd = ":",
        )
        """);
    addOptions("--nobuild");

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//fruit:apple"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:apple: "
            + "target '//fruit:banana__hdrs__' does not exist");

    assertThrows(ViewCreationFailedException.class, () -> buildTarget("//fruit:apple"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //fruit:apple: "
            + "target '//fruit:banana__hdrs__' does not exist");
  }

  @Test
  public void testBuildAllAndParsingError() throws Exception {
    write(
        "pkg/BUILD",
        "load('@rules_java//java:defs.bzl', 'java_binary')",
        "java_binary(",
        "name = \"foo\",",
        "  syntax error here",
        ")");

    addOptions("--nobuild");

    Exception e = assertThrows(Exception.class, () -> buildTarget("//pkg:all"));
    events.assertContainsError("syntax error at 'error'");
    assertPkgErrorMsg(e);
  }

  @Test
  public void testDiscardAnalysisCache() throws Exception {
    write(
        "sh/BUILD",
        """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "sh",
            srcs = [],
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            srcs = [],
        )
        """);
    buildTarget("//sh:sh");
    // We test with dep because we may keep references to the top-level configured targets.
    ConfiguredTarget ct = getConfiguredTarget("//sh:dep");
    addOptions("--discard_analysis_cache");
    buildTarget("//sh:sh");
    addOptions("--nodiscard_analysis_cache");
    buildTarget("//sh:sh");
    // Configured target was replaced.
    ConfiguredTarget newCt = getConfiguredTarget("//sh:dep");
    assertThat(newCt).isNotSameInstanceAs(ct);
    WeakReference<ConfiguredTarget> ref = new WeakReference<>(newCt);
    newCt = null;
    addOptions("--discard_analysis_cache");
    buildTarget("//sh:sh");
    GcFinalization.awaitClear(ref);
  }

  @Test
  public void testDiscardAnalysisCacheWithError() throws Exception {
    write(
        "x/BUILD",
        """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            deps = [":z__hdrs__"],
        )

        genrule(
            name = "z",
            outs = ["z.out"],
            cmd = ":",
        )
        """);
    write("y/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='y')");
    addOptions("--discard_analysis_cache", "--keep_going");
    EventCollector collector = new EventCollector(EventKind.STDERR);
    events.addHandler(collector);
    assertThrows(BuildFailedException.class, () -> buildTarget("//x:x", "//y:y"));
    events.assertContainsError(
        "in deps attribute of cc_library rule //x:x: target '//x:z__hdrs__' does not exist");
    MoreAsserts.assertContainsEvent(collector, "Target //y:y up-to-date", EventKind.STDERR);
  }

  @Test
  public void testBuildAllAndEvaluationError() throws Exception {
    write(
        "pkg/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "foo",
            srcs = unknown_value,
        )
        """);

    addOptions("--nobuild");

    Exception e = assertThrows(Exception.class, () -> buildTarget("//pkg:all"));
    events.assertContainsError("name 'unknown_value' is not defined");
    assertPkgErrorMsg(e);
  }

  private static void assertPkgErrorMsg(Exception e) {
    assertThat(e).hasMessageThat().containsMatch("[pP]ackage.*contains errors");
  }

  @Test
  public void testNoTestTargetsFoundMessageForBuildCommand() throws Exception {
    write("pkg/BUILD");
    for (String option : ImmutableList.of("", "--nobuild", "--noanalyze")) {
      setupOptions();
      addOptions(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS);
      addOptions(option);
      buildTarget("//pkg:all");
      assertDoesNotContainEvent("test target");
    }
  }
}
