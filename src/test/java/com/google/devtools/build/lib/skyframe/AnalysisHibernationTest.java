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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.devtools.build.lib.server.IdleTaskException;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ProviderSnapshot;
import com.google.devtools.build.skyframe.InMemoryGraph;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AnalysisHibernationTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void uncheckedIdleFailureIsRecoverableAndDoesNotPoisonLaterOperations() throws Exception {
    var graph = mock(InMemoryGraph.class);
    when(graph.getAllNodeEntries())
        .thenThrow(new IllegalStateException("injected"))
        .thenReturn(List.of());
    AtomicLong now = new AtomicLong();
    try (var controller = new AnalysisHibernation(now::get)) {
      var task =
          controller.idleTask(
              graph,
              temporary.newFolder().toPath(),
              Duration.ZERO,
              ProviderSnapshot.Limits.defaults());
      assertThat(assertThrows(IdleTaskException.class, task::run))
          .hasCauseThat()
          .hasCauseThat()
          .hasMessageThat()
          .isEqualTo("injected");
      assertThat(controller.takeWarning()).contains("five minutes");
      now.addAndGet(TimeUnit.MINUTES.toNanos(5));
      task.run();
      task.run();
      assertThat(controller.isHibernated()).isFalse();
    }
  }

  @Test
  public void transientFailureBacksOffWithoutPermanentlyDisablingRetries() throws Exception {
    var graph = mock(InMemoryGraph.class);
    AtomicInteger attempts = new AtomicInteger();
    when(graph.getAllNodeEntries())
        .thenAnswer(
            unused -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IOException("temporary storage pressure");
              }
              return List.of();
            });
    AtomicLong now = new AtomicLong();
    try (var controller = new AnalysisHibernation(now::get)) {
      var task =
          controller.idleTask(
              graph,
              temporary.newFolder().toPath(),
              Duration.ZERO,
              ProviderSnapshot.Limits.defaults());
      assertThrows(IdleTaskException.class, task::run);
      assertThat(controller.takeWarning()).contains("five minutes");
      assertThat(controller.takeWarning()).isNull();
      now.addAndGet(TimeUnit.MINUTES.toNanos(4));
      task.run();
      assertThat(attempts.get()).isEqualTo(1);
      now.addAndGet(TimeUnit.MINUTES.toNanos(1));
      task.run();
      assertThat(attempts.get()).isEqualTo(2);
      assertThat(controller.isHibernated()).isFalse();
    }
  }

  @Test
  public void budgetRejectionWarnsOnceAndSkipsRetriesUntilReset() throws Exception {
    var graph = mock(InMemoryGraph.class);
    AtomicInteger attempts = new AtomicInteger();
    when(graph.getAllNodeEntries())
        .thenAnswer(
            unused -> {
              if (attempts.incrementAndGet() == 1) {
                throw new com.google.devtools.build.lib.skyframe.serialization
                    .SerializationException(
                    "wrapped", new ProviderSnapshot.LimitExceededException("injected budget"));
              }
              return List.of();
            });
    try (var controller = new AnalysisHibernation()) {
      var task =
          controller.idleTask(
              graph,
              temporary.newFolder().toPath(),
              Duration.ZERO,
              ProviderSnapshot.Limits.defaults());
      assertThrows(IdleTaskException.class, task::run);
      task.run();
      assertThat(attempts.get()).isEqualTo(1);
      assertThat(controller.takeWarning()).contains("injected budget");
      assertThat(controller.takeWarning()).isNull();
      controller.discard();
      task.run();
      assertThat(attempts.get()).isEqualTo(2);
    }
  }

  @Test
  public void shutdownInterruptsActiveSelectionWithoutWaitingForItUnderLock() throws Exception {
    var graph = mock(InMemoryGraph.class);
    CountDownLatch selecting = new CountDownLatch(1);
    CountDownLatch parked = new CountDownLatch(1);
    when(graph.getAllNodeEntries())
        .thenAnswer(
            unused -> {
              selecting.countDown();
              parked.await();
              return List.of();
            });
    var controller = new AnalysisHibernation();
    var task =
        controller.idleTask(
            graph,
            temporary.newFolder().toPath(),
            Duration.ZERO,
            ProviderSnapshot.Limits.defaults());
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Thread writer =
        new Thread(
            () -> {
              try {
                task.run();
              } catch (Throwable failure) {
                outcome.set(failure);
              }
            });
    writer.start();
    try {
      assertThat(selecting.await(10, TimeUnit.SECONDS)).isTrue();
      controller.close();
      writer.join(TimeUnit.SECONDS.toMillis(10));
      assertThat(writer.isAlive()).isFalse();
      assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
      // Closure is sticky; a queued idle task cannot start another write after shutdown.
      task.run();
    } finally {
      parked.countDown();
      writer.interrupt();
      writer.join(TimeUnit.SECONDS.toMillis(10));
      controller.close();
    }
  }
}
