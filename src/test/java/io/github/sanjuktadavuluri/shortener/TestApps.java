package io.github.sanjuktadavuluri.shortener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Starts an extra, real instance of the application, e.g. to simulate a restart or check the
 * production defaults. Settings are passed as command-line arguments so they outrank {@code
 * application.properties}.
 */
final class TestApps {

  private TestApps() {}

  /** Starts the application on a random port against the given database file. */
  static ConfigurableApplicationContext start(Path database, String... extraArguments) {
    List<String> arguments = new ArrayList<>();
    arguments.add("--server.port=0");
    arguments.add("--shortener.database-path=" + database);
    arguments.addAll(List.of(extraArguments));
    return new SpringApplicationBuilder(SimpleUrlShortenerApplication.class)
        .run(arguments.toArray(String[]::new));
  }

  /** A {@link MockMvcTester} bound to a running instance. */
  static MockMvcTester mvc(ConfigurableApplicationContext app) {
    return MockMvcTester.create(
        MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build());
  }
}
