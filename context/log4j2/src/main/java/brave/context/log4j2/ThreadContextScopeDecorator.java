/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.context.log4j2;

import brave.baggage.BaggageFields;
import brave.baggage.CorrelationScopeDecorator;
import brave.internal.CorrelationContext;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import org.apache.logging.log4j.ThreadContext;

/**
 * Creates a {@link CorrelationScopeDecorator} for Log4j 2 {@linkplain ThreadContext Thread
 * Context}.
 *
 * <p>Ex.
 * <pre>{@code
 * tracing = Tracing.newBuilder()
 *                  .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder()
 *                    .addScopeDecorator(ThreadContextScopeDecorator.get())
 *                    .build()
 *                  )
 *                  ...
 *                  .build();
 * }</pre>
 *
 * @see CorrelationScopeDecorator
 */
public final class ThreadContextScopeDecorator {
  static final CurrentTraceContext.ScopeDecorator INSTANCE = new Builder().build();

  /**
   * Returns a singleton that configures {@link BaggageFields#TRACE_ID} and {@link
   * BaggageFields#SPAN_ID}.
   *
   * @since 5.11
   */
  public static CurrentTraceContext.ScopeDecorator get() {
    return INSTANCE;
  }

  /**
   * Returns a builder that configures {@link BaggageFields#TRACE_ID} and {@link
   * BaggageFields#SPAN_ID}.
   *
   * @since 5.11
   */
  public static CorrelationScopeDecorator.Builder newBuilder() {
    return new Builder();
  }

  static final class Builder extends CorrelationScopeDecorator.Builder {
    Builder() {
      super(ThreadContextCorrelationContext.INSTANCE);
    }
  }

  // TODO: see if we can read/write directly to skip some overhead similar to
  // https://github.com/census-instrumentation/opencensus-java/blob/2903747aca08b1e2e29da35c5527ff046918e562/contrib/log_correlation/log4j2/src/main/java/io/opencensus/contrib/logcorrelation/log4j2/OpenCensusTraceContextDataInjector.java
  enum ThreadContextCorrelationContext implements CorrelationContext {
    INSTANCE;

    @Override public String getValue(String name) {
      return ThreadContext.get(name);
    }

    @Override public boolean update(String name, @Nullable String value) {
      if (value != null) {
        ThreadContext.put(name, value);
      } else if (ThreadContext.containsKey(name)) {
        ThreadContext.remove(name);
      } else {
        return false;
      }
      return true;
    }
  }
}
