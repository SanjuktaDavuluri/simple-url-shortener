package io.github.sanjuktadavuluri.shortener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain HTTP requests to a running instance's real ports, for the seams MockMvc can't reach: the
 * management port (spec 0006 seam 2) and what the public port's servlet container really answers.
 */
final class PlainHttp {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PlainHttp() {}

  /** Sends {@code GET path} to {@code localhost:port}; redirects are not followed. */
  static HttpResponse<String> get(int port, String path) {
    return send(port, "GET", path);
  }

  /** Sends a body-less request with this method to {@code localhost:port}. */
  static HttpResponse<String> send(int port, String method, String path) {
    try (HttpClient http =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .method(method, HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A response's JSON body as nested maps, to compare whole with the expected document. */
  static Map<String, Object> json(HttpResponse<String> response) {
    return JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
  }
}
