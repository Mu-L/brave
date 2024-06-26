/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.grpc;

import brave.Clock;
import brave.CurrentSpanCustomizer;
import brave.ScopedSpan;
import brave.SpanCustomizer;
import brave.Tag;
import brave.handler.MutableSpan;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.rpc.RpcRequestParser;
import brave.rpc.RpcResponseParser;
import brave.rpc.RpcRuleSampler;
import brave.rpc.RpcTracing;
import brave.test.ITRemote;
import brave.test.util.AssertableCallback;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GraterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.internal.GrpcUtil;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static brave.Span.Kind.CLIENT;
import static brave.grpc.GreeterImpl.HELLO_REQUEST;
import static brave.rpc.RpcRequestMatchers.methodEquals;
import static brave.rpc.RpcRequestMatchers.serviceEquals;
import static brave.sampler.Sampler.ALWAYS_SAMPLE;
import static brave.sampler.Sampler.NEVER_SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public abstract class BaseITTracingClientInterceptor extends ITRemote { // public for src/it
  GrpcTracing grpcTracing = GrpcTracing.create(tracing);
  TestServer server = new TestServer(grpcTracing.nameToKey, grpcTracing.rpcTracing.propagation());
  ManagedChannel client;

  @BeforeEach protected void setup() throws IOException {
    server.start();
    client = newClient();
  }

  @AfterEach protected void close() {
    closeClient(client);
    server.stop();
  }

  ManagedChannel newClient() {
    return newClient(grpcTracing.newClientInterceptor());
  }

  ManagedChannel newClient(ClientInterceptor... clientInterceptors) {
    return usePlainText(ManagedChannelBuilder.forAddress("localhost", server.port())
        .intercept(clientInterceptors)).build();
  }

  /** Extracted as {@link ManagedChannelBuilder#usePlaintext()} is a version-specific signature */
  protected abstract ManagedChannelBuilder<?> usePlainText(ManagedChannelBuilder<?> localhost);

  @Test void propagatesNewTrace() {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isTrue();
    assertThat(extracted.parentIdString()).isNull();
    assertSameIds(testSpanHandler.takeRemoteSpan(CLIENT), extracted);
  }

  @Test void propagatesChildOfCurrentSpan() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isTrue();
    assertChildOf(extracted, parent);
    assertSameIds(testSpanHandler.takeRemoteSpan(CLIENT), extracted);
  }

  /** Unlike Brave 3, Brave 4 propagates trace ids even when unsampled */
  @Test void propagatesUnsampledContext() {
    TraceContext parent = newTraceContext(SamplingFlags.NOT_SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(extracted.sampled()).isFalse();
    assertChildOf(extracted, parent);
  }

  @Test void propagatesBaggage() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      BAGGAGE_FIELD.updateValue(parent, "joey");
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(BAGGAGE_FIELD.getValue(extracted)).isEqualTo("joey");

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void propagatesBaggage_unsampled() {
    TraceContext parent = newTraceContext(SamplingFlags.NOT_SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      BAGGAGE_FIELD.updateValue(parent, "joey");
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    }

    TraceContext extracted = server.takeRequest().context();
    assertThat(BAGGAGE_FIELD.getValue(extracted)).isEqualTo("joey");
  }

  /** This prevents confusion as a blocking client should end before, the start of the next span. */
  @Test void clientTimestampAndDurationEnclosedByParent() {
    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    Clock clock = tracing.clock(parent);

    long start = clock.currentTimeMicroseconds();
    try (Scope scope = currentTraceContext.newScope(parent)) {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    }
    long finish = clock.currentTimeMicroseconds();

    MutableSpan clientSpan = testSpanHandler.takeRemoteSpan(CLIENT);
    assertChildOf(clientSpan, parent);
    assertSpanInInterval(clientSpan, start, finish);
  }

  /**
   * This tests that the parent is determined at the time the request was made, not when the request
   * was executed.
   */
  @Test void usesParentFromInvocationTime() {
    server.enqueueDelay(TimeUnit.SECONDS.toMillis(1));
    GreeterGrpc.GreeterFutureStub futureStub = GreeterGrpc.newFutureStub(client);

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      futureStub.sayHello(HELLO_REQUEST);
      futureStub.sayHello(HELLO_REQUEST);
    }

    try (Scope scope = currentTraceContext.newScope(null)) {
      for (int i = 0; i < 2; i++) {
        TraceContext extracted = server.takeRequest().context();
        assertChildOf(extracted, parent);
      }
    }

    // The spans may report in a different order than the requests
    for (int i = 0; i < 2; i++) {
      assertChildOf(testSpanHandler.takeRemoteSpan(CLIENT), parent);
    }
  }

  @Test void reportsClientKindToZipkin() {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    testSpanHandler.takeRemoteSpan(CLIENT);
  }

  @Test void defaultSpanNameIsMethodName() {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).name())
        .isEqualTo("helloworld.Greeter/SayHello");
  }

  @Test void onTransportException_setsError() {
    server.stop();

    assertThatThrownBy(() -> GraterGrpc.newBlockingStub(client).seyHallo(HELLO_REQUEST))
        .isInstanceOf(StatusRuntimeException.class);

    // The error format of the exception message can differ from the span's "error" tag in CI
    MutableSpan span = testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, ".*Connection refused.*");
    assertThat(span.tags()).containsEntry("rpc.error_code", "UNAVAILABLE");
  }

  @Test void setsErrorTag_onUnimplemented() {
    assertThatThrownBy(() -> GraterGrpc.newBlockingStub(client).seyHallo(HELLO_REQUEST))
        .isInstanceOf(StatusRuntimeException.class);

    MutableSpan span = testSpanHandler.takeRemoteSpanWithErrorTag(CLIENT, "UNIMPLEMENTED");
    assertThat(span.tags().get("rpc.error_code")).isEqualTo("UNIMPLEMENTED");
  }

  @Test void setsErrorTag_onCanceledFuture() {
    server.enqueueDelay(TimeUnit.SECONDS.toMillis(1));

    ListenableFuture<HelloReply> resp = GreeterGrpc.newFutureStub(client).sayHello(HELLO_REQUEST);
    assumeTrue(resp.cancel(true), "lost race on cancel");

    MutableSpan span = testSpanHandler.takeRemoteSpanWithErrorTag(CLIENT, "CANCELLED");
    assertThat(span.tags().get("rpc.error_code")).isEqualTo("CANCELLED");
  }

  /**
   * NOTE: for this to work, the tracing interceptor must be last (so that it executes first)
   *
   * <p>Also notice that we are only making the current context available in the request side.
   */
  @Test void currentSpanVisibleToUserInterceptors() {
    closeClient(client);

    client = newClient(
        new ClientInterceptor() {
          @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
              @Override
              public void start(Listener<RespT> responseListener, Metadata headers) {
                tracing.tracer().currentSpanCustomizer().annotate("start");
                super.start(responseListener, headers);
              }

              @Override public void sendMessage(ReqT message) {
                tracing.tracer().currentSpanCustomizer().annotate("sendMessage");
                super.sendMessage(message);
              }
            };
          }
        },
        grpcTracing.newClientInterceptor()
    );

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).annotations())
        .extracting(Entry::getValue)
        .containsOnly("start", "sendMessage");
  }

  @Test void userInterceptor_throwsOnHalfClose() {
    closeClient(client);
    client = newClient(new ClientInterceptor() {
      @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions,
          Channel channel) {
        ClientCall<ReqT, RespT> call = channel.newCall(methodDescriptor, callOptions);
        return new SimpleForwardingClientCall<ReqT, RespT>(call) {
          @Override public void halfClose() {
            throw new IllegalStateException("I'm a bad interceptor.");
          }
        };
      }
    }, grpcTracing.newClientInterceptor());

    assertThatThrownBy(() -> GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST))
        .isInstanceOf(IllegalStateException.class);
    testSpanHandler.takeRemoteSpanWithErrorMessage(CLIENT, "I'm a bad interceptor.");
  }

  /**
   * This shows that a {@link ClientInterceptor} can see the server server span when processing the
   * request and response.
   */
  @Test void messageTagging_unary() {
    initMessageTaggingClient();

    ScopedSpan span = tracing.tracer().startScopedSpan("parent");
    try {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    } finally {
      span.finish();
    }

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).tags())
        .containsKey("grpc.message_send.1");

    // Response processing happens on the invocation (parent) trace context
    assertThat(testSpanHandler.takeLocalSpan().tags())
        .containsKey("grpc.message_recv.1");
  }

  @Test void messageTagging_streaming() {
    initMessageTaggingClient();

    ScopedSpan span = tracing.tracer().startScopedSpan("parent");
    try {
      Iterator<HelloReply> replies = GreeterGrpc.newBlockingStub(client)
          .sayHelloWithManyReplies(HELLO_REQUEST);
      assertThat(replies).toIterable().hasSize(10);
    } finally {
      span.finish();
    }

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).tags())
        .containsKey("grpc.message_send.1");

    // Response processing happens on the invocation (parent) trace context
    // Intentionally verbose here to show 10 replies
    assertThat(testSpanHandler.takeLocalSpan().tags()).containsKeys(
        "grpc.message_recv.1",
        "grpc.message_recv.2",
        "grpc.message_recv.3",
        "grpc.message_recv.4",
        "grpc.message_recv.5",
        "grpc.message_recv.6",
        "grpc.message_recv.7",
        "grpc.message_recv.8",
        "grpc.message_recv.9",
        "grpc.message_recv.10"
    );
  }

  void initMessageTaggingClient() {
    SpanCustomizer customizer = CurrentSpanCustomizer.create(tracing);
    AtomicInteger sends = new AtomicInteger(1);
    AtomicInteger recvs = new AtomicInteger(1);

    closeClient(client);
    client = newClient(
        new ClientInterceptor() {
          @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
              @Override public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override public void onMessage(RespT message) {
                    customizer.tag("grpc.message_recv." + recvs.getAndIncrement(),
                        message.toString());
                    delegate().onMessage(message);
                  }
                }, headers);
              }

              @Override public void sendMessage(ReqT message) {
                customizer.tag("grpc.message_send." + sends.getAndIncrement(), message.toString());
                delegate().sendMessage(message);
              }
            };
          }
        }, grpcTracing.newClientInterceptor());
  }

  /**
   * This ensures that response callbacks run in the invocation context, not the client one. This
   * allows async chaining to appear caused by the parent, not by the most recent client. Otherwise,
   * we would see a client span child of a client span, which could be confused with duplicate
   * instrumentation and affect dependency link counts.
   */
  @Test void callbackContextIsFromInvocationTime() {
    AssertableCallback<HelloReply> callback = new AssertableCallback<>();

    // Capture the current trace context when onSuccess or onError occur
    AtomicReference<TraceContext> invocationContext = new AtomicReference<>();
    callback.setListener(() -> invocationContext.set(currentTraceContext.get()));

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    try (Scope scope = currentTraceContext.newScope(parent)) {
      GreeterGrpc.newStub(client).sayHello(HELLO_REQUEST, new StreamObserverAdapter(callback));
    }

    callback.join(); // ensures listener ran
    assertThat(invocationContext.get()).isSameAs(parent);
    assertChildOf(testSpanHandler.takeRemoteSpan(CLIENT), parent);
  }

  /** This ensures that response callbacks run when there is no invocation trace context. */
  @Test void callbackContextIsFromInvocationTime_root() {
    AssertableCallback<HelloReply> callback = new AssertableCallback<>();

    // Capture the current trace context when onSuccess or onError occur
    AtomicReference<TraceContext> invocationContext = new AtomicReference<>();
    callback.setListener(() -> invocationContext.set(currentTraceContext.get()));

    GreeterGrpc.newStub(client).sayHello(HELLO_REQUEST, new StreamObserverAdapter(callback));

    callback.join(); // ensures listener ran
    assertThat(invocationContext.get()).isNull();
    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).parentId()).isNull();
  }

  /* RpcTracing-specific feature tests */

  @Test void customSampler() {
    closeClient(client);

    RpcTracing rpcTracing = RpcTracing.newBuilder(tracing).clientSampler(RpcRuleSampler.newBuilder()
        .putRule(methodEquals("SayHelloWithManyReplies"), NEVER_SAMPLE)
        .putRule(serviceEquals("helloworld.greeter"), ALWAYS_SAMPLE)
        .build()).build();

    grpcTracing = GrpcTracing.create(rpcTracing);
    client = newClient();

    // unsampled
    // NOTE: An iterator request is lazy: invoking the iterator invokes the request
    GreeterGrpc.newBlockingStub(client).sayHelloWithManyReplies(HELLO_REQUEST).hasNext();

    // sampled
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).name())
        .isEqualTo("helloworld.Greeter/SayHello");
    // @After will also check that sayHelloWithManyReplies was not sampled
  }

  @Test void customParser() {
    closeClient(client);

    Tag<GrpcRequest> methodType = new Tag<GrpcRequest>("grpc.method_type") {
      @Override protected String parseValue(GrpcRequest input, TraceContext context) {
        return input.methodDescriptor().getType().name();
      }
    };

    Tag<GrpcResponse> responseEncoding = new Tag<GrpcResponse>("grpc.response_encoding") {
      @Override protected String parseValue(GrpcResponse input, TraceContext context) {
        return input.headers().get(GrpcUtil.MESSAGE_ENCODING_KEY);
      }
    };

    grpcTracing = GrpcTracing.create(RpcTracing.newBuilder(tracing)
        .clientRequestParser((req, context, span) -> {
          RpcRequestParser.DEFAULT.parse(req, context, span);
          if (req instanceof GrpcRequest) methodType.tag((GrpcRequest) req, span);
        })
        .clientResponseParser((res, context, span) -> {
          RpcResponseParser.DEFAULT.parse(res, context, span);
          if (res instanceof GrpcResponse) responseEncoding.tag((GrpcResponse) res, span);
        }).build());

    client = newClient();

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(testSpanHandler.takeRemoteSpan(CLIENT).tags())
        .containsEntry("grpc.method_type", "UNARY")
        .containsEntry("grpc.response_encoding", "identity");
  }

  static final class StreamObserverAdapter implements StreamObserver<HelloReply> {
    final AssertableCallback<HelloReply> callback;

    StreamObserverAdapter(AssertableCallback<HelloReply> callback) {
      this.callback = callback;
    }

    @Override public void onNext(HelloReply helloReply) {
      callback.onSuccess(helloReply);
    }

    @Override public void onError(Throwable throwable) {
      callback.onError(throwable);
    }

    @Override public void onCompleted() {
    }
  }

  void closeClient(ManagedChannel client) {
    client.shutdown();
    try {
      client.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
