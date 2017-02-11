/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

final class RealCall implements Call {
  // 基于OKHttpClient的Call实现
  final OkHttpClient client;
  final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  /**
   * The application's original request unadulterated by redirects or auth headers.
   */
  final Request originalRequest;
  final boolean forWebSocket;

  // Guarded by this.
  private boolean executed;

  RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
  }

  @Override
  public Request request() {
    return originalRequest;
  }

  // 使用模式: 创建RealCall(xxx)之后，立即调用: execute
  @Override
  public Response execute() throws IOException {
    synchronized (this) {
      if (executed) {
        throw new IllegalStateException("Already Executed");
      }
      executed = true;
    }

    captureCallStackTrace();
    try {
      // 通过: dispatch来执行自己
      // 同步网络请求
      client.dispatcher().executed(this);

      Response result = getResponseWithInterceptorChain();

      if (result == null) {
        throw new IOException("Canceled");
      }
      return result;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  private void captureCallStackTrace() {
    Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  @Override
  public void enqueue(Callback responseCallback) {
    synchronized (this) {
      if (executed) {
        throw new IllegalStateException("Already Executed");
      }
      executed = true;
    }
    captureCallStackTrace();

    // 添加到client中，通过callback异步通知caller
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override
  public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override
  public synchronized boolean isExecuted() {
    return executed;
  }

  @Override
  public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override
  public RealCall clone() {
    return new RealCall(client, originalRequest, forWebSocket);
  }

  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    RealCall get() {
      return RealCall.this;
    }

    @Override
    protected void execute() {
      boolean signalledCallback = false;
      try {
        // 如何处理一个请求呢？
        // 获取Response
        Response response = getResponseWithInterceptorChain();

        // 处理各种不同的回调
        if (retryAndFollowUpInterceptor.isCanceled()) {
          signalledCallback = true;
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          // 在callback中可以逐步处理这个response
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();

    // 这些Interceptors是如何工作的呢?
    //
    interceptors.addAll(client.interceptors());

    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));

    // 缓存管理
    interceptors.add(new CacheInterceptor(client.internalCache())); // 缓存的管理
    // 连接管理
    interceptors.add(new ConnectInterceptor(client));

    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }

    // 最后一步: 直接访问网路?
    interceptors.add(new CallServerInterceptor(forWebSocket));

    // 将全部的Interceptors构成一个Chain
    Interceptor.Chain chain = new RealInterceptorChain(
        interceptors, null, null, null, 0, originalRequest);

    // 开始处理整个Chain
    return chain.proceed(originalRequest);
  }
}
