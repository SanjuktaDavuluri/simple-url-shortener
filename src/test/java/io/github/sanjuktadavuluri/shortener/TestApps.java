package io.github.sanjuktadavuluri.shortener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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
    return startWithEnvironment(database, Map.of(), extraArguments);
  }

  /**
   * Starts the application as {@link #start} does, as if the process also had these environment
   * variables (added to the real ones, which stay in place).
   */
  static ConfigurableApplicationContext startWithEnvironment(
      Path database, Map<String, String> environmentVariables, String... extraArguments) {
    return run(database, environmentVariables, List.of(), extraArguments);
  }

  /**
   * Starts the application as {@link #start} does, with these {@code @TestConfiguration} classes
   * added to it, e.g. to swap in a stand-in bean marked {@code @Primary} for one instance only.
   */
  static ConfigurableApplicationContext startWithConfigurations(
      Path database, List<Class<?>> configurations, String... extraArguments) {
    return run(database, Map.of(), configurations, extraArguments);
  }

  private static ConfigurableApplicationContext run(
      Path database,
      Map<String, String> environmentVariables,
      List<Class<?>> configurations,
      String... extraArguments) {
    List<String> arguments = new ArrayList<>();
    arguments.add("--server.port=0");
    arguments.add("--shortener.database-path=" + database);
    arguments.addAll(List.of(extraArguments));
    Map<String, Object> variables = new HashMap<>(System.getenv());
    variables.putAll(environmentVariables);
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    List<Class<?>> sources = new ArrayList<>();
    sources.add(SimpleUrlShortenerApplication.class);
    sources.addAll(configurations);
    return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
        .environment(environment)
        .run(arguments.toArray(String[]::new));
  }

  /** The port a running instance's web server listens on. */
  static int port(ConfigurableApplicationContext app) {
    return Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port"));
  }

  /** A {@link MockMvcTester} bound to a running instance. */
  static MockMvcTester mvc(ConfigurableApplicationContext app) {
    return MockMvcTester.create(
        MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build());
  }
}
