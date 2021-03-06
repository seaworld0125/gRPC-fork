/*
 * Copyright 2016 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.stub;

import javax.annotation.Nullable;

/**
 * A refinement of {@link CallStreamObserver} that allows for lower-level interaction with
 * client calls. An instance of this class is obtained via {@link ClientResponseObserver}, or by
 * manually casting the {@code StreamObserver} returned by a stub.
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and make a fake for the server-side.
 */
public abstract class ClientCallStreamObserver<ReqT> extends CallStreamObserver<ReqT> {
  /**
   * Prevent any further processing for this {@code ClientCallStreamObserver}. No further messages
   * will be received. The server is informed of cancellations, but may not stop processing the
   * call. Cancelling an already
   * {@code cancel()}ed {@code ClientCallStreamObserver} has no effect.
   *
   * <p>No other methods on this class can be called after this method has been called.
   *
   * <p>It is recommended that at least one of the arguments to be non-{@code null}, to provide
   * useful debug information. Both argument being null may log warnings and result in suboptimal
   * performance. Also note that the provided information will not be sent to the server.
   *
   * @param message if not {@code null}, will appear as the description of the CANCELLED status
   * @param cause if not {@code null}, will appear as the cause of the CANCELLED status
   */
  public abstract void cancel(@Nullable String message, @Nullable Throwable cause);

  /**
   * Swaps to manual flow control where no message will be delivered to {@link
   * StreamObserver#onNext(Object)} unless it is {@link #request request()}ed. Since {@code
   * request()} may not be called before the call is started, a number of initial requests may be
   * specified.
   *
   * <p>This method may only be called during {@link ClientResponseObserver#beforeStart
   * ClientResponseObserver.beforeStart()}.
   */
  public void disableAutoRequestWithInitial(int request) {
    throw new UnsupportedOperationException();
  }

  /**
   * If {@code true}, indicates that the observer is capable of sending additional messages
   * without requiring excessive buffering internally. This value is just a suggestion and the
   * application is free to ignore it, however doing so may result in excessive buffering within the
   * observer.
   *
   * <p>If {@code false}, the runnable passed to {@link #setOnReadyHandler} will be called after
   * {@code isReady()} transitions to {@code true}.
   */
  @Override
  public abstract boolean isReady();

  /**
   * Set a {@link Runnable} that will be executed every time the stream {@link #isReady()} state
   * changes from {@code false} to {@code true}.  While it is not guaranteed that the same
   * thread will always be used to execute the {@link Runnable}, it is guaranteed that executions
   * are serialized with calls to the 'inbound' {@link StreamObserver}.
   *
   * <p>May only be called during {@link ClientResponseObserver#beforeStart}.
   *
   * <p>Because there is a processing delay to deliver this notification, it is possible for
   * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
   * notifications by checking {@code isReady()}'s current value instead of assuming it is now
   * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
   * <em>another</em> {@code onReadyHandler} callback.
   *
   * @param onReadyHandler to call when peer is ready to receive more messages.
   */
  @Override
  public abstract void setOnReadyHandler(Runnable onReadyHandler);

  /**
   * Requests the peer to produce {@code count} more messages to be delivered to the 'inbound'
   * {@link StreamObserver}.
   *
   * <p>This method is safe to call from multiple threads without external synchronization.
   *
   * @param count more messages
   */
  @Override
  public abstract void request(int count);

  /**
   * Sets message compression for subsequent calls to {@link #onNext}.
   *
   * @param enable whether to enable compression.
   */
  @Override
  public abstract void setMessageCompression(boolean enable);
}
