/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.baggage;

import brave.baggage.Access;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Injector;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static brave.baggage.BaggagePropagation.newFactoryBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** This is an internal feature until we settle on an encoding format. */
class BaggageInSingleHeaderTest {
  BaggageField field1 = BaggageField.create("one");
  BaggageField field2 = BaggageField.create("two");
  BaggageField field3 = BaggageField.create("three");

  Propagation.Factory factory = newFactoryBuilder(B3Propagation.newFactoryBuilder()
      .injectFormat(B3Propagation.Format.SINGLE).build())
      .add(SingleBaggageField.remote(field1))
      .add(SingleBaggageField.local(field2))
      .add(Access.newBaggagePropagationConfig(SingleHeaderCodec.get(), 32))
      .build();

  /** This shows that we can encode arbitrary fields into a single header. */
  @Test void encodes_arbitrary_fields() {
    TraceContext context = factory.decorate(TraceContext.newBuilder().traceId(1).spanId(2).build());
    field1.updateValue(context, "1");
    field2.updateValue(context, "2");
    field3.updateValue(context, "3");

    Injector<Map<String, String>> injector = factory.get().injector(Map::put);
    Map<String, String> headers = new LinkedHashMap<>();
    injector.inject(context, headers);

    assertThat(headers).containsOnly(
        entry("b3", "0000000000000001-0000000000000002"),
        entry("one", "1"), // has its own header config which is still serialized
        entry("baggage", "one=1,three=3") // excluding the blacklist field including the dynamic one
    );
  }
}
