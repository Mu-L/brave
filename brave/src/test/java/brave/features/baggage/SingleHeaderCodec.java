/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.baggage;

import brave.baggage.BaggageField;
import brave.baggage.BaggageField.ValueUpdater;
import brave.internal.baggage.BaggageCodec;
import brave.internal.codec.EntrySplitter;
import brave.propagation.TraceContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This is a non-complete codec for the w3c (soon to be renamed to "baggage") header.
 *
 * <p>See https://github.com/w3c/correlation-context/blob/master/correlation_context/HTTP_HEADER_FORMAT.md
 */
final class SingleHeaderCodec implements BaggageCodec, EntrySplitter.Handler<ValueUpdater> {
  static final EntrySplitter ENTRY_SPLITTER = EntrySplitter.newBuilder().build();
  static final SingleHeaderCodec INSTANCE = new SingleHeaderCodec();

  static BaggageCodec get() {
    return INSTANCE;
  }

  final String keyName = "baggage";
  final List<String> keyNames = Collections.singletonList(keyName);

  @Override public List<String> extractKeyNames() {
    return keyNames;
  }

  @Override public List<String> injectKeyNames() {
    return keyNames;
  }

  @Override public boolean decode(ValueUpdater valueUpdater, String value) {
    return ENTRY_SPLITTER.parse(this, valueUpdater, value);
  }

  @Override public boolean onEntry(ValueUpdater target,
    CharSequence buffer, int beginKey, int endKey, int beginValue, int endValue) {
    BaggageField field = BaggageField.create(buffer.subSequence(beginKey, endKey).toString());
    String value = buffer.subSequence(beginValue, endValue).toString();
    return target.updateValue(field, value);
  }

  @Override public String encode(Map<String, String> values, TraceContext context) {
    StringBuilder result = new StringBuilder();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (result.length() > 0) result.append(',');
      result.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return result.length() == 0 ? null : result.toString();
  }
}
