// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Runs cleanup-related tasks during an idle period in the server.
 *
 * <p>A fresh instance must be constructed to manage each individual idle period. The idle period
 * begins when {@link #idle} is called and ends when {@link #busy} is called.
 */
public final class IdleTaskManager {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private enum State {
    INITIALIZED,
    IDLE,
    BUSY
  }

  private static final class IdleTaskWrapper implements Callable<IdleTask.Result> {
    private final IdleTask task;

    IdleTaskWrapper(IdleTask task) {
      this.task = task;
    }

    @Override
    public IdleTask.Result call() {
      String name = task.displayName();
      Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        logger.atInfo().log("%s idle task started", name);
        task.run();
        logger.atInfo().log("%s idle task finished", name);
        return new IdleTask.Result(name, IdleTask.Status.SUCCESS, stopwatch.elapsed());
      } catch (IdleTaskException e) {
        logger.atWarning().withCause(e.getCause()).log("%s idle task failed", name);
        return new IdleTask.Result(name, IdleTask.Status.FAILURE, stopwatch.elapsed());
      } catch (InterruptedException e) {
        // There's no point in restoring the interrupt bit since this thread belongs to an executor
        // service that is shutting down.
        logger.atWarning().withCause(e).log("%s idle task interrupted", name);
        return new IdleTask.Result(name, IdleTask.Status.INTERRUPTED, stopwatch.elapsed());
      }
    }
  }

  private volatile State state = State.INITIALIZED;

  // Use a single-threaded ScheduledThreadPoolExecutor to ensure that tasks execute serially.
  private final ScheduledThreadPoolExecutor executor =
      new ScheduledThreadPoolExecutor(
          1, new ThreadFactoryBuilder().setNameFormat("idle-server-tasks-%d").build());

  private final ImmutableList<IdleTask> idleTasks;

  private final ArrayList<TaskExecution> taskExecutions;

  /**
   * Creates a new {@link IdleTaskManager}.
   *
   * @param idleTasks tasks to run while idle
   */
  public IdleTaskManager(ImmutableList<IdleTask> idleTasks) {
    this.idleTasks = idleTasks;
    this.taskExecutions = new ArrayList<>(idleTasks.size());
  }

  /**
   * Called by the main thread when the server becomes idle.
   *
   * <p>Does not block, but may schedule tasks in the background.
   */
  public synchronized void idle() {
    checkState(state == State.INITIALIZED);
    state = State.IDLE;

    for (IdleTask task : idleTasks) {
      Duration repeatDelay = task.repeatDelay();
      checkArgument(
          repeatDelay == null || (!repeatDelay.isNegative() && !repeatDelay.isZero()),
          "repeatDelay must be positive for task %s: %s",
          task.displayName(),
          repeatDelay);
      TaskExecution taskExecution = new TaskExecution(task, repeatDelay);
      taskExecutions.add(taskExecution);
      taskExecution.schedule(task.delay().toSeconds(), SECONDS);
    }
  }

  /**
   * Called by the main thread when the server gets to work.
   *
   * <p>Interrupts any pending idle tasks and blocks for their completion before returning.
   *
   * @return stats for each idle task, in the same order they were registered
   */
  public synchronized ImmutableList<IdleTask.Result> busy() {
    checkState(state == State.IDLE);
    state = State.BUSY;

    // Interrupt pending tasks.
    var unused = executor.shutdownNow();

    // Wait for all tasks to complete, so they cannot interfere with a subsequent command.
    Uninterruptibles.awaitTerminationUninterruptibly(executor);

    ImmutableList.Builder<IdleTask.Result> results =
        ImmutableList.builderWithExpectedSize(idleTasks.size());

    for (int i = 0; i < idleTasks.size(); i++) {
      IdleTask task = idleTasks.get(i);
      String name = task.displayName();
      TaskExecution taskExecution = taskExecutions.get(i);
      IdleTask.Result result;
      if (taskExecution.failure != null) {
        throw new IllegalStateException(
            "Unexpected exception thrown by idle task", taskExecution.failure);
      }
      // For a repeated task, report the most recent completed execution. If a later scheduled
      // repetition was cancelled before starting, retain the previous result.
      result =
          taskExecution.lastResult != null
              ? taskExecution.lastResult
              : new IdleTask.Result(name, IdleTask.Status.NOT_STARTED, Duration.ZERO);
      results.add(result);
    }

    return results.build();
  }

  /** Tracks the latest result and schedules another execution when requested. */
  private final class TaskExecution implements Runnable {
    private final IdleTask task;
    @Nullable private final Duration repeatDelay;

    @Nullable private volatile IdleTask.Result lastResult;
    @Nullable private volatile Throwable failure;

    TaskExecution(IdleTask task, @Nullable Duration repeatDelay) {
      this.task = task;
      this.repeatDelay = repeatDelay;
    }

    void schedule(long delay, TimeUnit unit) {
      try {
        var unused = executor.schedule(this, delay, unit);
      } catch (RejectedExecutionException e) {
        // busy() shuts the executor down after setting the state to BUSY. A racing reschedule is
        // therefore expected to be rejected and must not interfere with the next command.
        if (state == State.IDLE) {
          throw e;
        }
      }
    }

    @Override
    public void run() {
      IdleTask.Result result = null;
      try {
        result = new IdleTaskWrapper(task).call();
        lastResult = result;
      } catch (RuntimeException | Error e) {
        failure = e;
        throw e;
      } finally {
        if (repeatDelay != null
            && result != null
            && result.status() != IdleTask.Status.INTERRUPTED
            && state == State.IDLE) {
          schedule(repeatDelay.toNanos(), NANOSECONDS);
        }
      }
    }
  }
}
