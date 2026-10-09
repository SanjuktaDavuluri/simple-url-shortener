package io.github.sanjuktadavuluri.shortener;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fixed head of the generated OpenAPI definition (spec 0008): a stable title and version, and
 * the relative server {@code /}, so no host or port from the generating instance leaks in.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  @Bean
  OpenAPI shortenerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Simple URL Shortener API")
                .version("1")
                .description("The public JSON API of the URL shortener."))
        .servers(List.of(new Server().url("/")));
  }
}
