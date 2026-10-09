package io.github.sanjuktadavuluri.shortener;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns {@code LOG_FORMAT} into Spring Boot's console logging format (spec 0006, ADR 0015):
 * {@code json} (the default) is the built-in structured ECS format, {@code text} is Spring's plain
 * pattern. Anything else stops startup with a message naming {@code LOG_FORMAT}, so a typo can't
 * silently change what the operator's log pipeline receives.
 *
 * <p>Runs before the logging system starts, so the very first line already has the right format.
 */
public class LogFormatEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String VARIABLE = "LOG_FORMAT";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String format = environment.getProperty(VARIABLE, "json").trim();
    switch (format) {
      case "json" ->
          environment
              .getPropertySources()
              .addLast(
                  new MapPropertySource(
                      "logFormat", Map.of("logging.structured.format.console", "ecs")));
      case "text" -> {}
      default ->
          throw new IllegalStateException(
              "LOG_FORMAT must be \"json\" or \"text\", but was \"" + format + "\"");
    }
  }
}
