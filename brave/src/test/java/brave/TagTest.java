/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TagTest {
  Span span = mock(Span.class);
  ScopedSpan scopedSpan = mock(ScopedSpan.class);
  SpanCustomizer customizer = mock(SpanCustomizer.class);
  MutableSpan mutableSpan = new MutableSpan();
  BiFunction<Object, TraceContext, String> parseValue = mock(BiFunction.class);

  Object input = new Object();
  TraceContext context = TraceContext.newBuilder().traceId(1).spanId(2).build();
  Tag<Object> tag = new Tag<Object>("key") {
    @Override protected String parseValue(Object input, TraceContext context) {
      return parseValue.apply(input, context);
    }
  };

  @BeforeEach void setup() {
    when(span.context()).thenReturn(context);
    when(scopedSpan.context()).thenReturn(context);
  }

  @Test void trimsKey() {
    assertThat(new Tag<Object>(" x-foo  ") {
      @Override protected String parseValue(Object input, TraceContext context) {
        return null;
      }
    }.key()).isEqualTo("x-foo");
  }

  @Test void key_invalid() {
    assertThatThrownBy(() -> new Tag<Object>(null) {
      @Override protected String parseValue(Object input, TraceContext context) {
        return null;
      }
    }).isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> new Tag<Object>("") {
      @Override protected String parseValue(Object input, TraceContext context) {
        return null;
      }
    }).isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> new Tag<Object>("   ") {
      @Override protected String parseValue(Object input, TraceContext context) {
        return null;
      }
    }).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void tag_span() {
    when(parseValue.apply(input, context)).thenReturn("value");

    tag.tag(input, span);

    verify(span).context();
    verify(span).isNoop();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(span).tag("key", "value");
    verifyNoMoreInteractions(span); // doesn't tag twice
  }

  @Test void tag_span_empty() {
    when(parseValue.apply(input, context)).thenReturn("");

    tag.tag(input, span);

    verify(span).context();
    verify(span).isNoop();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(span).tag("key", "");
    verifyNoMoreInteractions(span); // doesn't tag twice
  }

  @Test void tag_span_doesntParseNoop() {
    when(span.isNoop()).thenReturn(true);

    verifyNoMoreInteractions(parseValue); // parsing is lazy
    verifyNoMoreInteractions(span);
  }

  @Test void tag_span_ignoredErrorParsing() {
    when(parseValue.apply(input, context)).thenThrow(new Error());

    tag.tag(input, span);

    verify(span).context();
    verify(span).isNoop();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verifyNoMoreInteractions(span);
  }

  @Test void tag_scopedSpan() {
    when(parseValue.apply(input, context)).thenReturn("value");

    tag.tag(input, scopedSpan);

    verify(scopedSpan).isNoop();
    verify(scopedSpan).context();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(scopedSpan).tag("key", "value");
    verifyNoMoreInteractions(scopedSpan); // doesn't tag twice
  }

  @Test void tag_scopedSpan_empty() {
    when(parseValue.apply(input, context)).thenReturn("");

    tag.tag(input, scopedSpan);

    verify(scopedSpan).isNoop();
    verify(scopedSpan).context();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(scopedSpan).tag("key", "");
    verifyNoMoreInteractions(scopedSpan); // doesn't tag twice
  }

  @Test void tag_scopedSpan_doesntParseNoop() {
    when(scopedSpan.isNoop()).thenReturn(true);

    verifyNoMoreInteractions(parseValue); // parsing is lazy
    verifyNoMoreInteractions(scopedSpan);
  }

  @Test void tag_scopedSpan_ignoredErrorParsing() {
    when(parseValue.apply(input, context)).thenThrow(new Error());

    tag.tag(input, scopedSpan);

    verify(scopedSpan).isNoop();
    verify(scopedSpan).context();
    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verifyNoMoreInteractions(scopedSpan);
  }

  @Test void tag_customizer() {
    when(parseValue.apply(input, null)).thenReturn("value");

    tag.tag(input, customizer);

    verify(parseValue).apply(input, null);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(customizer).tag("key", "value");
    verifyNoMoreInteractions(customizer); // doesn't tag twice
  }

  @Test void tag_customizer_empty() {
    when(parseValue.apply(input, null)).thenReturn("");

    tag.tag(input, customizer);

    verify(parseValue).apply(input, null);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(customizer).tag("key", "");
    verifyNoMoreInteractions(customizer); // doesn't tag twice
  }

  @Test void tag_customizer_doesntParseNoop() {
    tag.tag(input, context, NoopSpanCustomizer.INSTANCE);

    verifyNoMoreInteractions(parseValue); // parsing is lazy
  }

  @Test void tag_customizer_ignoredErrorParsing() {
    when(parseValue.apply(input, null)).thenThrow(new Error());

    tag.tag(input, customizer);

    verify(parseValue).apply(input, null);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verifyNoMoreInteractions(customizer);
  }

  @Test void tag_customizer_withNullContext() {
    when(parseValue.apply(eq(input), isNull())).thenReturn("value");

    tag.tag(input, null, customizer);

    verify(parseValue).apply(input, null);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(customizer).tag("key", "value");
    verifyNoMoreInteractions(customizer); // doesn't tag twice
  }

  @Test void tag_customizer_withContext() {
    when(parseValue.apply(input, context)).thenReturn("value");

    tag.tag(input, context, customizer);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(customizer).tag("key", "value");
    verifyNoMoreInteractions(customizer); // doesn't tag twice
  }

  @Test void tag_customizer_withContext_empty() {
    when(parseValue.apply(input, context)).thenReturn("");

    tag.tag(input, context, customizer);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verify(customizer).tag("key", "");
    verifyNoMoreInteractions(customizer); // doesn't tag twice
  }

  @Test void tag_customizer_withContext_doesntParseNoop() {
    tag.tag(input, context, NoopSpanCustomizer.INSTANCE);

    verifyNoMoreInteractions(parseValue); // parsing is lazy
  }

  @Test void tag_customizer_withContext_ignoredErrorParsing() {
    when(parseValue.apply(input, context)).thenThrow(new Error());

    tag.tag(input, context, customizer);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice
    verifyNoMoreInteractions(customizer);
  }

  @Test void tag_mutableSpan() {
    when(parseValue.apply(input, context)).thenReturn("value");

    tag.tag(input, context, mutableSpan);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice

    MutableSpan expected = new MutableSpan();
    expected.tag("key", "value");
    assertThat(mutableSpan).isEqualTo(expected);
  }

  @Test
  public void tag_mutableSpan_threadSafe() throws InterruptedException {
    int numThreads = 1000;
    ExecutorService service = Executors.newFixedThreadPool(numThreads);
    try {
      for (int i = 0; i < numThreads; i++) {
        String val = String.valueOf(i);
        Tag<Object> tag = new Tag<Object>("key" + i) {
          @Override protected String parseValue(Object input, TraceContext context) {
            return val;
          }
        };
        service.submit(() -> tag.tag(input, context, mutableSpan));
      }
    } finally {
      service.shutdown();
      service.awaitTermination(1, TimeUnit.MINUTES);
    }
    assertThat(mutableSpan.tagCount()).isEqualTo(numThreads);
  }

  @Test void tag_mutableSpan_nullContext() {
    when(parseValue.apply(eq(input), isNull())).thenReturn("value");

    tag.tag(input, null, mutableSpan);

    verify(parseValue).apply(input, null);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice

    MutableSpan expected = new MutableSpan();
    expected.tag("key", "value");
    assertThat(mutableSpan).isEqualTo(expected);
  }

  @Test void tag_mutableSpan_empty() {
    when(parseValue.apply(input, context)).thenReturn("");

    tag.tag(input, context, mutableSpan);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice

    MutableSpan expected = new MutableSpan();
    expected.tag("key", "");
    assertThat(mutableSpan).isEqualTo(expected);
  }

  @Test void tag_mutableSpan_ignoredErrorParsing() {
    when(parseValue.apply(input, context)).thenThrow(new Error());

    tag.tag(input, context, mutableSpan);

    verify(parseValue).apply(input, context);
    verifyNoMoreInteractions(parseValue); // doesn't parse twice

    assertThat(mutableSpan.error()).isNull();
  }
}
