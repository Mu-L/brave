/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.grpc;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class GrpcClientResponseTest {
  Key<String> b3Key = Key.of("b3", Metadata.ASCII_STRING_MARSHALLER);
  MethodDescriptor<?, ?> methodDescriptor = TestObjects.METHOD_DESCRIPTOR;
  CallOptions callOptions = CallOptions.DEFAULT;
  ClientCall<?, ?> call = mock(ClientCall.class);
  Metadata headers = new Metadata(), trailers = new Metadata();
  GrpcClientRequest request =
      new GrpcClientRequest(singletonMap("b3", b3Key), methodDescriptor, callOptions, call,
          headers);
  Status status = Status.CANCELLED;
  GrpcClientResponse response = new GrpcClientResponse(request, headers, status, trailers);

  @Test void request() {
    assertThat(response.request()).isSameAs(request);
  }

  @Test void headers() {
    assertThat(response.headers()).isSameAs(headers);
  }

  @Test void status() {
    assertThat(response.status()).isSameAs(status);
  }

  @Test void unwrap() {
    assertThat(response.unwrap()).isSameAs(status);
  }

  @Test void trailers() {
    assertThat(response.trailers()).isSameAs(trailers);
  }

  @Test void error_null() {
    assertThat(response.error()).isNull();
  }

  @Test void error_fromStatus() {
    RuntimeException error = new RuntimeException("noodles");
    status = Status.fromThrowable(error);
    GrpcClientResponse response = new GrpcClientResponse(request, headers, status, trailers);

    assertThat(response.error()).isSameAs(error);
    assertThat(response.errorCode()).isEqualTo("UNKNOWN");
  }

  @Test void errorCode_nullWhenOk() {
    status = Status.OK;
    GrpcClientResponse response = new GrpcClientResponse(request, headers, status, trailers);

    assertThat(response.errorCode()).isNull();
  }

  @Test void errorCode() {
    assertThat(response.errorCode()).isEqualTo("CANCELLED");
  }
}
