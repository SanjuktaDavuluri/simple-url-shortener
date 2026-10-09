package io.github.sanjuktadavuluri.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimpleUrlShortenerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SimpleUrlShortenerApplication.class, args);
    exitZeroOnSigterm();
  }

  /**
   * A requested stop (SIGTERM, as {@code docker stop} sends) is a clean exit, so the process exits
   * 0 instead of the JVM's 143. {@code System.exit} runs the same shutdown hooks, so the graceful
   * shutdown order (spec 0006) is unchanged.
   */
  @SuppressWarnings("SunApi")
  private static void exitZeroOnSigterm() {
    sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> System.exit(0));
  }
}
