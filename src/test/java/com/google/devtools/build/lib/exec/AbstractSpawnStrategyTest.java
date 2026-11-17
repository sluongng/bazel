// Copyright 2017 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnExecutedEvent;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.exec.SpawnCache.CacheHandle;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SortedMap;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link BlazeExecutor}. */
@RunWith(TestParameterInjector.class)
public class AbstractSpawnStrategyTest {
  private static final FailureDetail NON_ZERO_EXIT_DETAILS =
      FailureDetail.newBuilder()
          .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NON_ZERO_EXIT))
          .build();

  private static class TestedSpawnStrategy extends AbstractSpawnStrategy {
    TestedSpawnStrategy(SpawnRunner spawnRunner) {
      super(spawnRunner, Options.getDefaults(ExecutionOptions.class));
    }

    TestedSpawnStrategy(SpawnRunner spawnRunner, ExecutionOptions executionOptions) {
      super(spawnRunner, executionOptions);
    }
  }

  private static final Spawn SIMPLE_SPAWN =
      new SpawnBuilder("/bin/echo", "Hi!").withEnvironment("VARIABLE", "value").build();

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fs.getPath("/execroot");
  private final FileOutErr outErr =
      new FileOutErr(execRoot.getChild("stdout"), execRoot.getChild("stderr"));
  @Mock private SpawnRunner spawnRunner;
  @Mock private ActionExecutionContext actionExecutionContext;
  private StoredEventHandler eventHandler;
  private final ManualClock clock = new ManualClock();

  @Before
  public final void setUp() throws Exception {
    execRoot.createDirectoryAndParents();
    MockitoAnnotations.initMocks(this);
    eventHandler = new StoredEventHandler();
    when(actionExecutionContext.getEventHandler()).thenReturn(eventHandler);
    when(actionExecutionContext.getClock()).thenReturn(clock);
    when(actionExecutionContext.getFileOutErr()).thenReturn(outErr);
  }

  @Test
  public void oversizedOutputFailsBeforeCacheStore(@TestParameter boolean stderr) throws Exception {
    // Set up a successful subprocess that writes directly to a captured stream.
    FileSystemUtils.writeContentAsLatin1(
        stderr ? outErr.getErrorPath() : outErr.getOutputPath(), "12345");
    SpawnResult result =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(), any())).thenReturn(result);
    SpawnCache cache = mock(SpawnCache.class);
    CacheHandle entry = mock(CacheHandle.class);
    when(cache.lookup(any(), any())).thenReturn(entry);
    when(entry.willStore()).thenReturn(true);
    when(actionExecutionContext.getContext(SpawnCache.class)).thenReturn(cache);
    SpawnLogContext spawnLogContext = mock(SpawnLogContext.class);
    when(actionExecutionContext.getContext(SpawnLogContext.class)).thenReturn(spawnLogContext);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    ExecutionOptions options =
        Options.parse(ExecutionOptions.class, "--max_action_stdout_stderr_bytes=4").getOptions();

    // Execute the spawn with a cap below the captured size.
    SpawnExecException failure =
        assertThrows(
            SpawnExecException.class,
            () ->
                new TestedSpawnStrategy(spawnRunner, options)
                    .exec(SIMPLE_SPAWN, actionExecutionContext));

    // Report the same policy failure to the caller and the event bus.
    assertThat(failure.getSpawnResult().failureDetail().getSpawn().getCode())
        .isEqualTo(Code.OUTPUT_TOO_LARGE);
    assertThat(failure.getSpawnResult().status()).isEqualTo(Status.OUTPUT_TOO_LARGE);
    SpawnExecutedEvent event = (SpawnExecutedEvent) eventHandler.getPosts().get(0);
    assertThat(event.getSpawnResult()).isSameInstanceAs(failure.getSpawnResult());
    assertThat(failure).hasMessageThat().contains("--max_action_stdout_stderr_bytes=4");
    // Keep the local bytes for diagnosis, but close the cache entry without storing.
    assertThat(stderr ? outErr.errAsLatin1() : outErr.outAsLatin1()).isEqualTo("12345");
    verify(entry, never()).store(any());
    verify(entry).close();
    // Log the rejected result rather than the successful subprocess result.
    verify(spawnLogContext)
        .logSpawn(
            eq(SIMPLE_SPAWN),
            any(),
            ArgumentMatchers.<Supplier<SortedMap<PathFragment, ActionInput>>>any(),
            eq(fs),
            eq(Duration.ZERO),
            eq(failure.getSpawnResult()));
  }

  @Test
  public void outputLimitPreservesOriginalResult(
      @TestParameter({"TIMEOUT", "EXECUTION_DENIED"}) Status status) throws Exception {
    FileSystemUtils.writeContentAsLatin1(outErr.getErrorPath(), "12345");
    int exitCode = status == Status.TIMEOUT ? SpawnResult.POSIX_TIMEOUT_EXIT_CODE : 2;
    FailureDetail detail =
        FailureDetail.newBuilder()
            .setSpawn(
                FailureDetails.Spawn.newBuilder()
                    .setCode(status == Status.TIMEOUT ? Code.TIMEOUT : Code.EXECUTION_DENIED))
            .build();
    SpawnResult result =
        new SpawnResult.Builder()
            .setStatus(status)
            .setExitCode(exitCode)
            .setFailureDetail(detail)
            .setRunnerName("test")
            .build();
    when(spawnRunner.exec(any(), any())).thenReturn(result);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    ExecutionOptions options =
        Options.parse(ExecutionOptions.class, "--max_action_stdout_stderr_bytes=4").getOptions();

    SpawnExecException failure =
        assertThrows(
            SpawnExecException.class,
            () ->
                new TestedSpawnStrategy(spawnRunner, options)
                    .exec(SIMPLE_SPAWN, actionExecutionContext));

    // Preserve the original execution result; enforce the cap only if a subprocess ran.
    assertThat(failure.getSpawnResult().status()).isEqualTo(status);
    assertThat(failure.getSpawnResult().exitCode()).isEqualTo(exitCode);
    assertThat(failure.getSpawnResult().failureDetail()).isEqualTo(detail);
    assertThat(failure.getSpawnResult().hasOversizedOutput()).isEqualTo(status == Status.TIMEOUT);
    if (status == Status.TIMEOUT) {
      String detailMessage =
          failure.getSpawnResult().getDetailMessage(failure.getMessage(), false, false);
      assertThat(detailMessage).contains(failure.getMessage());
      assertThat(detailMessage).contains("--max_action_stdout_stderr_bytes=4");
    }
  }

  @Test
  public void remoteOutputIsExemptFromLocalLimit() throws Exception {
    // A remote result is accepted even if its captured log exceeds the local limit.
    FileSystemUtils.writeContentAsLatin1(outErr.getErrorPath(), "remote stderr");
    SpawnResult result =
        new SpawnResult.Builder()
            .setStatus(Status.SUCCESS)
            .setRunnerName("remote")
            .setRemote(true)
            .build();
    when(spawnRunner.exec(any(), any())).thenReturn(result);
    ExecutionOptions options =
        Options.parse(ExecutionOptions.class, "--max_action_stdout_stderr_bytes=4").getOptions();

    assertThat(
            new TestedSpawnStrategy(spawnRunner, options)
                .exec(SIMPLE_SPAWN, actionExecutionContext))
        .containsExactly(result);
  }

  @Test
  public void outputLimitDefaultsToOneGiB() {
    assertThat(Options.getDefaults(ExecutionOptions.class).getMaxActionStdoutStderrBytes())
        .isEqualTo(1L << 30);
  }

  @Test
  public void testZeroExit() throws Exception {
    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(SpawnCache.NO_CACHE);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext);

    assertThat(spawnResults).containsExactly(spawnResult);

    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
  }

  @Test
  public void testEventPosting() throws Exception {
    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(SpawnCache.NO_CACHE);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    Instant beforeTime = Instant.ofEpochMilli(clock.currentTimeMillis());
    doAnswer(
            invocation -> {
              clock.advanceMillis(1);
              return spawnResult;
            })
        .when(spawnRunner)
        .exec(any(Spawn.class), any(SpawnExecutionContext.class));

    ImmutableList<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext);

    assertThat(spawnResults).containsExactly(spawnResult);
    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    assertThat(eventHandler.getPosts()).hasSize(1);
    SpawnExecutedEvent event = (SpawnExecutedEvent) eventHandler.getPosts().get(0);
    assertThat(event.getStartTimeInstant()).isEqualTo(beforeTime);
    assertThat(event.getSpawnResult()).isEqualTo(spawnResult);
    assertThat(event.getExitCode()).isEqualTo(0);
  }

  @Test
  public void testNonZeroExit() throws Exception {
    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(SpawnCache.NO_CACHE);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult result =
        new SpawnResult.Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setFailureDetail(NON_ZERO_EXIT_DETAILS)
            .setRunnerName("test")
            .build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(result);

    SpawnExecException e =
        assertThrows(
            SpawnExecException.class,
            () ->
                // Ignoring the List<SpawnResult> return value.
                new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext));
    assertThat(e.getSpawnResult()).isSameInstanceAs(result);
    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
  }

  @Test
  public void testCacheHit() throws Exception {
    SpawnCache cache = mock(SpawnCache.class);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(cache.lookup(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(SpawnCache.success(spawnResult));
    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext);
    assertThat(spawnResults).containsExactly(spawnResult);
    verify(spawnRunner, never()).exec(any(Spawn.class), any(SpawnExecutionContext.class));
  }

  @Test
  public void testCacheMiss(@TestParameter({"0", "4"}) long limit) throws Exception {
    // Both streams may reach the per-stream limit; their sum is not capped.
    FileSystemUtils.writeContentAsLatin1(outErr.getOutputPath(), "1234");
    FileSystemUtils.writeContentAsLatin1(outErr.getErrorPath(), "5678");
    if (limit == 0) {
      // Model a stream beyond the default cap without allocating a multi-GiB fixture.
      FileOutErr largeOutErr = spy(outErr);
      doReturn(2L << 30).when(largeOutErr).outSize();
      when(actionExecutionContext.getFileOutErr()).thenReturn(largeOutErr);
    }
    ExecutionOptions options =
        Options.parse(ExecutionOptions.class, "--max_action_stdout_stderr_bytes=" + limit)
            .getOptions();
    SpawnCache cache = mock(SpawnCache.class);
    CacheHandle entry = mock(CacheHandle.class);
    when(cache.lookup(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(entry);
    when(entry.hasResult()).thenReturn(false);
    when(entry.willStore()).thenReturn(true);

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner, options).exec(SIMPLE_SPAWN, actionExecutionContext);

    assertThat(spawnResults).containsExactly(spawnResult);

    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    verify(entry).store(eq(spawnResult));
  }

  @Test
  public void testExec_whenLocalCaches_usesNoCache() throws Exception {
    when(spawnRunner.handlesCaching()).thenReturn(true);

    SpawnCache cache = mock(SpawnCache.class);

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext);

    assertThat(spawnResults).containsExactly(spawnResult);

    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    verifyNoInteractions(cache);
  }

  @Test
  public void testExec_usefulCacheInDynamicExecution() throws Exception {
    when(spawnRunner.handlesCaching()).thenReturn(false);

    SpawnCache cache = mock(SpawnCache.class);
    when(cache.usefulInDynamicExecution()).thenReturn(true);
    CacheHandle entry = mock(CacheHandle.class);
    when(cache.lookup(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(entry);
    when(entry.hasResult()).thenReturn(false);
    when(entry.willStore()).thenReturn(true);

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner)
            .exec(SIMPLE_SPAWN, actionExecutionContext, (exitCode, errorMessage, outErr) -> {});

    assertThat(spawnResults).containsExactly(spawnResult);

    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    verify(entry).store(eq(spawnResult));
  }

  @Test
  public void testExec_nonUsefulCacheInDynamicExecution() throws Exception {
    when(spawnRunner.handlesCaching()).thenReturn(false);

    SpawnCache cache = mock(SpawnCache.class);
    when(cache.usefulInDynamicExecution()).thenReturn(false);

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    List<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner)
            .exec(SIMPLE_SPAWN, actionExecutionContext, (exitCode, errorMessage, outErr) -> {});

    assertThat(spawnResults).containsExactly(spawnResult);

    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    verify(cache).usefulInDynamicExecution();
    verifyNoMoreInteractions(cache);
  }

  @Test
  public void testCacheMissWithNonZeroExit() throws Exception {
    SpawnCache cache = mock(SpawnCache.class);
    CacheHandle entry = mock(CacheHandle.class);
    when(cache.lookup(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(entry);
    when(entry.hasResult()).thenReturn(false);
    when(entry.willStore()).thenReturn(true);

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(cache);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    SpawnResult result =
        new SpawnResult.Builder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(1)
            .setFailureDetail(NON_ZERO_EXIT_DETAILS)
            .setRunnerName("test")
            .build();
    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class))).thenReturn(result);

    SpawnExecException e =
        assertThrows(
            SpawnExecException.class,
            () ->
                // Ignoring the List<SpawnResult> return value.
                new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext));
    assertThat(e.getSpawnResult()).isSameInstanceAs(result);
    // Must only be called exactly once.
    verify(spawnRunner).exec(any(Spawn.class), any(SpawnExecutionContext.class));
    verify(entry).store(eq(result));
  }

  @Test
  public void testExec_callsLogSpawn() throws Exception {
    FileSystem actionFs = mock(FileSystem.class);
    InputMetadataProvider inputMetadataProvider = mock(InputMetadataProvider.class);
    SpawnLogContext spawnLogContext = mock(SpawnLogContext.class);

    SpawnResult spawnResult =
        new SpawnResult.Builder().setStatus(Status.SUCCESS).setRunnerName("test").build();

    when(actionExecutionContext.getContext(eq(SpawnCache.class))).thenReturn(SpawnCache.NO_CACHE);
    when(actionExecutionContext.getContext(eq(SpawnLogContext.class))).thenReturn(spawnLogContext);
    when(actionExecutionContext.getExecRoot()).thenReturn(execRoot);
    when(actionExecutionContext.getActionFileSystem()).thenReturn(actionFs);
    when(actionExecutionContext.getInputMetadataProvider()).thenReturn(inputMetadataProvider);

    when(spawnRunner.exec(any(Spawn.class), any(SpawnExecutionContext.class)))
        .thenReturn(spawnResult);

    ImmutableList<SpawnResult> spawnResults =
        new TestedSpawnStrategy(spawnRunner).exec(SIMPLE_SPAWN, actionExecutionContext);
    assertThat(spawnResults).containsExactly(spawnResult);

    verify(spawnLogContext)
        .logSpawn(
            eq(SIMPLE_SPAWN),
            eq(inputMetadataProvider),
            ArgumentMatchers.<Supplier<SortedMap<PathFragment, ActionInput>>>any(),
            eq(actionFs),
            eq(Duration.ZERO),
            eq(spawnResult));
  }
}
