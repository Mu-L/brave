/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.features.handler;

import brave.GarbageCollectors;
import brave.ScopedSpan;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationUpdater;
import brave.handler.MutableSpan.TagUpdater;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.Map.Entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** One reason {@link brave.handler.MutableSpan} is mutable is to support redaction */
class RedactingSpanHandlerTest {
  /**
   * This is just a dummy pattern. See <a href="https://github.com/ExpediaDotCom/haystack-secrets-commons/blob/master/src/main/java/com/expedia/www/haystack/commons/secretDetector/HaystackCompositeCreditCardFinder.java">HaystackCompositeCreditCardFinder</a>
   * for a realistic one.
   */
  static final Pattern CREDIT_CARD = Pattern.compile("[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{4}");

  enum ValueRedactor implements TagUpdater, AnnotationUpdater {
    INSTANCE;

    @Override public String update(String key, String value) {
      return maybeUpdateValue(value);
    }

    @Override public String update(long timestamp, String value) {
      return maybeUpdateValue(value);
    }

    /** Simple example of a replacement pattern, deleting entries which only include credit cards */
    static String maybeUpdateValue(String value) {
      Matcher matcher = CREDIT_CARD.matcher(value);
      if (matcher.find()) {
        String matched = matcher.group(0);
        if (matched.equals(value)) return null;
        return value.replace(matched, "xxxx-xxxx-xxxx-xxxx");
      }
      return value;
    }
  }

  BlockingQueue<MutableSpan> spans = new LinkedBlockingQueue<>();
  SpanHandler redacter = new SpanHandler() {
    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      span.forEachTag(ValueRedactor.INSTANCE);
      span.forEachAnnotation(ValueRedactor.INSTANCE);
      return true;
    }
  };

  Tracing tracing = Tracing.newBuilder()
    .addSpanHandler(redacter)
    .addSpanHandler(new SpanHandler() {
      @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        spans.add(span);
        return true;
      }
    })
    .build();

  @AfterEach void close() {
    tracing.close();
  }

  @Test void showRedaction() throws Exception {
    ScopedSpan span = tracing.tracer().startScopedSpan("auditor");
    try {
      span.tag("a", "1");
      span.tag("b", "4121-2319-1483-3421");
      span.annotate("cc=4121-2319-1483-3421");
      span.tag("c", "3");
    } finally {
      span.finish();
    }

    MutableSpan reported = spans.take();
    assertThat(reported.tags()).containsExactly(
      entry("a", "1"),
      // credit card tag was nuked
      entry("c", "3")
    );
    assertThat(reported.annotations()).extracting(Entry::getValue).containsExactly(
      "cc=xxxx-xxxx-xxxx-xxxx"
    );

    // Leak some data by adding a tag using the same context after the span was .
    tracing.tracer().toSpan(span.context()).tag("d", "cc=4121-2319-1483-3421");
    span = null; // Orphans are via GC, to test this, we have to drop any reference to the context
    GarbageCollectors.blockOnGC();

    // GC only clears the reference to the leaked data. Normal tracer use implicitly handles orphans
    tracing.tracer().nextSpan().abandon();

    MutableSpan leaked = spans.take();
    assertThat(leaked.tags()).containsExactly(
      // credit card tag was nuked
      entry("d", "cc=xxxx-xxxx-xxxx-xxxx")
    );
  }
}
