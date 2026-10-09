package io.github.sanjuktadavuluri.shortener;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every public request its Request ID (spec 0006, ADR 0015): the identifier a client or a
 * person reporting a problem can quote, and the operator can find the request by.
 *
 * <p>An incoming {@code X-Request-Id} of 1–64 characters from {@code [A-Za-z0-9._-]} is used as is,
 * so a client's own correlation ID lines up with ours. Anything else, or no header, is replaced by
 * a new random UUID, and the rejected value is never logged, so a caller can't inject fake log
 * lines or fields. The ID goes into the logging MDC as {@code request_id} and into the {@code
 * X-Request-Id} response header <em>before</em> the rest of the chain runs, so every response
 * carries it (errors and the Redirect included), and it is taken out of the MDC afterwards, even
 * when the chain throws, so it never leaks onto the next request handled by the same thread.
 *
 * <p>Ordered first. It is a bean of the application context, which serves the public port only:
 * Actuator's separate management port runs in its own child context with its own filters, so health
 * and metrics responses are untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

  /** The request and response header that carries the Request ID. */
  static final String HEADER = "X-Request-Id";

  /** The MDC key, which structured logging turns into a field of every line. */
  static final String MDC_KEY = "request_id";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = requestIdFor(request.getHeader(HEADER));
    response.setHeader(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** The incoming value if it is safe to use as is, otherwise a new random UUID. */
  private static String requestIdFor(String incoming) {
    if (incoming != null && SAFE.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString();
  }
}
