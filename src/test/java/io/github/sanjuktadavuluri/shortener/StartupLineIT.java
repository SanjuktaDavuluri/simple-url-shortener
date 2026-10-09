package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Issue #121 (spec 0006 story 32, seam 3): once ready, the app logs exactly one startup line whose
 * fields list the effective non-secret settings.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupLineIT {

  @Test
  void onceReadyExactlyOneStartupLineListsTheEffectiveSettings(CapturedOutput out) {
    Path database = TestDatabases.newFile();
    try (ConfigurableApplicationContext app =
        TestApps.start(
            database,
            "--shortener.base-url=http://short.test/",
            "--shortener.clicks.queue-capacity=77",
            "--shortener.clicks.batch-size=7",
            "--shortener.clicks.flush-interval=3s",
            "--shortener.clicks.shutdown-timeout=5s",
            "--SHUTDOWN_TIMEOUT=4s")) {
      List<JsonNode> lines =
          LogLines.parseJsonLines(out).stream()
              .filter(line -> "Startup settings".equals(LogLines.text(line, "message")))
              .toList();

      assertThat(lines).hasSize(1);
      JsonNode line = lines.get(0);
      assertThat(LogLines.level(line)).isEqualTo("INFO");
      assertThat(LogLines.text(line, "port")).isEqualTo(String.valueOf(TestApps.port(app)));
      assertThat(LogLines.text(line, "management_port"))
          .isEqualTo(String.valueOf(TestApps.managementPort(app)));
      assertThat(LogLines.text(line, "base_url")).isEqualTo("http://short.test");
      assertThat(LogLines.text(line, "database_path")).isEqualTo(database.toString());
      assertThat(LogLines.text(line, "click_queue_capacity")).isEqualTo("77");
      assertThat(LogLines.text(line, "click_batch_size")).isEqualTo("7");
      assertThat(LogLines.text(line, "click_flush_interval")).isEqualTo("PT3S");
      assertThat(LogLines.text(line, "shutdown_timeout")).isEqualTo("4s");
      assertThat(LogLines.text(line, "click_shutdown_timeout")).isEqualTo("PT5S");
      assertThat(LogLines.text(line, "log_format")).isEqualTo("json");
    }
  }
}
