package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Issue #117 (spec 0006 stories 20–23, Implementation Decisions: Request ID): while the rest of the
 * chain handles a request, its Request ID is in the logging MDC as {@code request_id} and already
 * in the {@code X-Request-Id} response header; once the filter returns, normally or by an
 * exception, the MDC no longer carries it, so it can't leak onto the next request on the thread.
 */
class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @AfterEach
  void leaveTheMdcEmpty() {
    MDC.clear();
  }

  @Test
  void theRequestIdIsInTheMdcAndTheResponseHeaderWhileTheChainRunsAndGoneAfterwards()
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/Ab3xK9q");
    request.addHeader("X-Request-Id", "abc-123_DEF.4");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inMdc = new AtomicReference<>();
    AtomicReference<String> inHeader = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (req, res) -> {
          inMdc.set(MDC.get("request_id"));
          inHeader.set(response.getHeader("X-Request-Id"));
        });

    assertThat(inMdc.get()).isEqualTo("abc-123_DEF.4");
    assertThat(inHeader.get()).isEqualTo("abc-123_DEF.4");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123_DEF.4");
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void theRequestIdIsGoneFromTheMdcEvenWhenTheChainThrows() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> inMdc = new AtomicReference<>();
    FilterChain failing =
        (req, res) -> {
          inMdc.set(MDC.get("request_id"));
          throw new IllegalStateException("The stand-in handler always fails");
        };

    assertThatCode(() -> filter.doFilter(new MockHttpServletRequest("GET", "/"), response, failing))
        .doesNotThrowAnyException();

    assertThat(response.getStatus()).isEqualTo(500);

    assertThat(inMdc.get()).isNotBlank().isEqualTo(response.getHeader("X-Request-Id"));
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void aRequestWithoutTheHeaderGetsAGeneratedRandomUuid() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest("GET", "/"), response, (req, res) -> {});

    assertThat(response.getHeader("X-Request-Id"))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }
}
