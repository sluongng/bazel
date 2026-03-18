// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor;
import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType;
import com.google.devtools.build.lib.concurrent.QuiescingExecutor;
import com.google.devtools.build.skyframe.ParallelEvaluatorContext.RunnableMaker;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link NodeEntryVisitor}. */
@RunWith(JUnit4.class)
public class NodeEntryVisitorTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private MultiThreadPoolsQuiescingExecutor executor;
  @Mock private InflightTrackingProgressReceiver receiver;
  @Mock private RunnableMaker runnableMaker;
  @Mock private Cache<SkyKey, SkyKeyComputeState> stateCache;

  @Test
  public void enqueueEvaluation_multiThreadPoolsQuiescingExecutor_nonCPUHeavyKey() {
    NodeEntryVisitor nodeEntryVisitor =
        new NodeEntryVisitor(executor, receiver, runnableMaker, stateCache);
    SkyKey nonCPUHeavyKey = mock(SkyKey.class);

    nodeEntryVisitor.enqueueEvaluation(nonCPUHeavyKey, null);

    verify(executor).execute(any(), eq(ThreadPoolType.REGULAR), anyBoolean());
  }

  @Test
  public void enqueueEvaluation_multiThreadPoolsQuiescingExecutor_cpuHeavyKey() {
    NodeEntryVisitor nodeEntryVisitor =
        new NodeEntryVisitor(executor, receiver, runnableMaker, stateCache);
    CPUHeavySkyKey cpuHeavyKey = mock(CPUHeavySkyKey.class);

    nodeEntryVisitor.enqueueEvaluation(cpuHeavyKey, null);

    verify(executor).execute(any(), eq(ThreadPoolType.CPU_HEAVY), anyBoolean());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void enqueueEvaluation_priorityFunctionOrdersSlowerActionsFirst() {
    SkyKey slowKey = mock(SkyKey.class);
    SkyKey fastKey = mock(SkyKey.class);
    RecordingQuiescingExecutor executor = new RecordingQuiescingExecutor();
    NodeEntryVisitor nodeEntryVisitor =
        new NodeEntryVisitor(
            executor, receiver, runnableMaker, stateCache, key -> key == slowKey ? 20L : 5L);

    when(runnableMaker.make(slowKey)).thenReturn(() -> executor.runOrder.add("slow"));
    when(runnableMaker.make(fastKey)).thenReturn(() -> executor.runOrder.add("fast"));

    nodeEntryVisitor.enqueueEvaluation(fastKey, null);
    nodeEntryVisitor.enqueueEvaluation(slowKey, null);

    assertThat(executor.runnables).hasSize(2);
    PriorityQueue<Comparable> priorityQueue = new PriorityQueue<>();
    for (Runnable runnable : executor.runnables) {
      assertThat(runnable).isInstanceOf(Comparable.class);
      priorityQueue.add((Comparable) runnable);
    }

    while (!priorityQueue.isEmpty()) {
      ((Runnable) priorityQueue.poll()).run();
    }

    assertThat(executor.runOrder).containsExactly("slow", "fast").inOrder();
  }

  private static final class RecordingQuiescingExecutor implements QuiescingExecutor {
    private final List<Runnable> runnables = new ArrayList<>();
    private final List<String> runOrder = new ArrayList<>();

    @Override
    public void execute(Runnable runnable) {
      runnables.add(runnable);
    }

    @Override
    public void awaitQuiescence(boolean interruptWorkers) {}

    @Override
    public void awaitQuiescenceWithoutShutdown(boolean interruptWorkers) {}

    @Override
    public void dependOnFuture(ListenableFuture<?> future) {}

    @Override
    public CountDownLatch getExceptionLatchForTestingOnly() {
      return new CountDownLatch(0);
    }

    @Override
    public CountDownLatch getInterruptionLatchForTestingOnly() {
      return new CountDownLatch(0);
    }
  }
}
