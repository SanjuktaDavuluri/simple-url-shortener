package io.github.sanjuktadavuluri.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimpleUrlShortenerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SimpleUrlShortenerApplication.class, args);
  }
}
