/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.jaxrs2;

import brave.SpanCustomizer;
import javax.inject.Inject;
import javax.ws.rs.ConstrainedTo;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import static javax.ws.rs.RuntimeType.SERVER;

/**
 * Adds application-tier data to an existing http span via {@link ContainerParser}.
 *
 * <p>Use this when you are tracing at a lower layer with {@code brave.servlet.TracingFilter}.
 */
// Currently not using PreMatching because we are attempting to detect if the method is async or not
@Provider
@ConstrainedTo(SERVER)
public final class SpanCustomizingContainerFilter implements ContainerRequestFilter {

  public static SpanCustomizingContainerFilter create() {
    return create(new ContainerParser());
  }

  public static SpanCustomizingContainerFilter create(ContainerParser parser) {
    return new SpanCustomizingContainerFilter(parser);
  }

  final ContainerParser parser;

  @Inject SpanCustomizingContainerFilter(ContainerParser parser) {
    this.parser = parser;
  }

  /** {@link PreMatching} cannot be used: pre-matching doesn't inject the resource info! */
  @Context ResourceInfo resourceInfo;

  @Override public void filter(ContainerRequestContext request) {
    SpanCustomizer span = (SpanCustomizer) request.getProperty(SpanCustomizer.class.getName());
    if (span != null && resourceInfo != null) {
      parser.resourceInfo(resourceInfo, span);
    }
  }
}
