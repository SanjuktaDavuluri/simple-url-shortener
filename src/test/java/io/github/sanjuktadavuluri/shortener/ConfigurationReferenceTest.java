package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Spec 0006, Configuration reference drift: the runbook documents every environment variable. */
class ConfigurationReferenceTest {

  private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
  private static final Path RUNBOOK = Path.of("docs/runbook.md");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):");

  @Test
  void everyEnvironmentVariableInApplicationPropertiesIsInTheRunbooksConfigurationTable()
      throws IOException {
    List<String> names = placeholderNames(Files.readString(PROPERTIES, StandardCharsets.UTF_8));
    String table = configurationTable(Files.readString(RUNBOOK, StandardCharsets.UTF_8));

    assertThat(names).isNotEmpty();
    for (String name : names) {
      assertThat(table)
          .as("%s in the runbook's configuration table", name)
          .contains("`" + name + "`");
    }
  }

  @Test
  void placeholderNamesAreCollectedFromEveryLine() {
    assertThat(placeholderNames("a=${ONE:1}\nb=x${TWO:2}y ${THREE:3}\nc=${shortener.x}"))
        .containsExactly("ONE", "TWO", "THREE");
  }

  private static List<String> placeholderNames(String properties) {
    List<String> names = new ArrayList<>();
    Matcher m = PLACEHOLDER.matcher(properties);
    while (m.find()) {
      names.add(m.group(1));
    }
    return names;
  }

  /** The table rows under the "Configuration reference" heading, up to the next heading. */
  private static String configurationTable(String runbook) {
    int start = runbook.indexOf("## Configuration reference");
    assertThat(start).as("a '## Configuration reference' section").isNotNegative();
    int end = runbook.indexOf("\n## ", start + 1);
    String section = end < 0 ? runbook.substring(start) : runbook.substring(start, end);
    StringBuilder rows = new StringBuilder();
    for (String line : section.split("\n")) {
      if (line.startsWith("|")) {
        rows.append(line).append('\n');
      }
    }
    return rows.toString();
  }
}
