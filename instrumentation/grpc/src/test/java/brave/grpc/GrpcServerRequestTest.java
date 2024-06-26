/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.grpc;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GrpcServerRequestTest {
  Key<String> b3Key = Key.of("b3", Metadata.ASCII_STRING_MARSHALLER);
  MethodDescriptor<?, ?> methodDescriptor = TestObjects.METHOD_DESCRIPTOR;
  ServerCall call = mock(ServerCall.class);
  Metadata headers = new Metadata();
  GrpcServerRequest request =
    new GrpcServerRequest(singletonMap("b3", b3Key), call, headers);

  @Test void service() {
    when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

    assertThat(request.service()).isEqualTo("helloworld.Greeter");
  }

  @Test void method() {
    when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

    assertThat(request.service()).isEqualTo("helloworld.Greeter");
  }

  @Test void unwrap() {
    assertThat(request.unwrap()).isSameAs(call);
  }

  @Test void call() {
    assertThat(request.call()).isSameAs(call);
  }

  @Test void propagationField() {
    headers.put(b3Key, "1");

    assertThat(request.propagationField("b3")).isEqualTo("1");
  }

  @Test void propagationField_null() {
    assertThat(request.propagationField("b3")).isNull();
  }

  @Test void propagationField_lastValue() {
    headers.put(b3Key, "0");
    headers.put(b3Key, "1");

    assertThat(request.propagationField("b3")).isEqualTo("1");
  }
}
