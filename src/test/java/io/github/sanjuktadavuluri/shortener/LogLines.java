package io.github.sanjuktadavuluri.shortener;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses captured console output into JSON log lines, so tests assert on fields (seam 3). */
final class LogLines {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private LogLines() {}

  /** Every non-blank captured line, each of which must be exactly one JSON object. */
  static List<JsonNode> parseAll(CapturedOutput out) {
    return Arrays.stream(out.getAll().split("\\R"))
        .filter(line -> !line.isBlank())
        .map(
            line -> {
              JsonNode node = JSON.readTree(line);
              if (!node.isObject()) {
                throw new AssertionError("Not a JSON object: " + line);
              }
              return node;
            })
        .toList();
  }

  /** Only the lines that start like a JSON object (for instances that also print a banner). */
  static List<JsonNode> parseJsonLines(CapturedOutput out) {
    return Arrays.stream(out.getAll().split("\\R"))
        .filter(line -> line.startsWith("{"))
        .map(JSON::readTree)
        .toList();
  }

  /** The access lines for a method and route (they are the ones that carry a route). */
  static List<JsonNode> accessLines(CapturedOutput out, String method, String route) {
    return parseAll(out).stream()
        .filter(l -> method.equals(text(l, "http_method")) && route.equals(text(l, "route")))
        .toList();
  }

  /** A field by its name, whether the format writes it flat ({@code a.b}) or nested. */
  static String text(JsonNode line, String name) {
    JsonNode node = line.get(name);
    if (node == null) {
      node = line;
      for (String part : name.split("\\.")) {
        node = node == null ? null : node.get(part);
      }
    }
    return node == null || node.isNull() ? null : node.asString();
  }

  static String timestamp(JsonNode line) {
    return text(line, "@timestamp");
  }

  static String level(JsonNode line) {
    return text(line, "log.level");
  }

  static String logger(JsonNode line) {
    return text(line, "log.logger");
  }
}
