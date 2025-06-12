// Copyright 2023 The Bazel Authors.
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

package com.google.devtools.build.lib.remote;

import com.google.common.flogger.GoogleLogger;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ClientInterceptor} that cancels a gRPC call if no response messages are received for a
 * configured idle timeout duration. The timeout is reset every time a {@code onMessage} is
 * delivered to the listener.
 *
 * <p>This is primarily used for ByteStream Read calls where large blobs are downloaded in a
 * streamed fashion. If the server stops sending data, we cancel the call so that the higher level
 * retry logic can attempt the download again.
 */
class IdleTimeoutInterceptor implements ClientInterceptor {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** Prefix of the full method name for ByteStream.Read calls. */
  private static final String BYTESTREAM_READ_METHOD_FULL_NAME =
      "google.bytestream.ByteStream/Read";

  /** Shared scheduler for all idle timeout tasks. */
  private static final ScheduledExecutorService scheduler;

  static {
    ThreadFactory factory =
        new ThreadFactory() {
          private final AtomicInteger ctr = new AtomicInteger();

          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "grpc-idle-timeout-" + ctr.getAndIncrement());
            t.setDaemon(true);
            return t;
          }
        };
    scheduler = Executors.newSingleThreadScheduledExecutor(factory);
  }

  private final long idleTimeoutMillis;

  IdleTimeoutInterceptor(Duration idleTimeout) {
    this.idleTimeoutMillis = idleTimeout.toMillis();
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, io.grpc.Channel next) {
    // We only care about ByteStream.Read streaming responses. For all other methods we just
    // delegate directly.
    if (!BYTESTREAM_READ_METHOD_FULL_NAME.equals(method.getFullMethodName())
        || idleTimeoutMillis <= 0) {
      return next.newCall(method, callOptions);
    }

    ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);

    return new SimpleForwardingClientCall<ReqT, RespT>(delegate) {

      private ScheduledFuture<?> idleFuture;

      private void cancelIdleTask() {
        if (idleFuture != null) {
          idleFuture.cancel(/* mayInterruptIfRunning= */ false);
          idleFuture = null;
        }
      }

      private void scheduleIdleCancellation() {
        cancelIdleTask();
        idleFuture =
            scheduler.schedule(
                () -> {
                  logger.atWarning().log(
                      "gRPC ByteStream.Read call idle for %d ms, cancelling", idleTimeoutMillis);
                  try {
                    // Use DEADLINE_EXCEEDED to ensure the higher level logic treats this as a
                    // retriable error.
                    cancel(
                        "idle timeout exceeded",
                        Status.DEADLINE_EXCEEDED
                            .withDescription("Idle timeout exceeded")
                            .asRuntimeException());
                  } catch (StatusRuntimeException e) {
                    // Ignore; call may already be closed.
                  }
                },
                idleTimeoutMillis,
                TimeUnit.MILLISECONDS);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        scheduleIdleCancellation();
        super.start(
            new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onMessage(RespT message) {
                // Reset idle timeout on every message.
                scheduleIdleCancellation();
                super.onMessage(message);
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                cancelIdleTask();
                super.onClose(status, trailers);
              }
            },
            headers);
      }

      @Override
      public void cancel(String message, Throwable cause) {
        cancelIdleTask();
        super.cancel(message, cause);
      }
    };
  }
}
