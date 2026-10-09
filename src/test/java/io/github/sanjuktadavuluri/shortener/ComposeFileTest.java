package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Spec 0007, Compose drift: compose.yaml passes exactly the runbook's documented settings. */
class ComposeFileTest {

  private static final Path COMPOSE = Path.of("compose.yaml");
  private static final Path RUNBOOK = Path.of("docs/runbook.md");

  /** Documented settings that Compose deliberately doesn't pass (the image fixes them). */
  private static final Set<String> NOT_PASSED = Set.of("DATABASE_PATH");

  private static final Pattern ROW = Pattern.compile("^\\|\\s*`([A-Z][A-Z0-9_]*)`");
  private static final Pattern ENV_LINE = Pattern.compile("^\\s+([A-Z][A-Z0-9_]*):");

  @Test
  void composePassesNoSettingTheRunbookDoesNotDocument() throws IOException {
    Set<String> passed = passedNames(read(COMPOSE));
    assertThat(passed).isNotEmpty();
    assertThat(documentedNames(read(RUNBOOK))).containsAll(passed);
  }

  @Test
  void composePassesEverySettingTheRunbookDocumentsExceptTheFixedOnes() throws IOException {
    Set<String> expected = documentedNames(read(RUNBOOK));
    expected.removeAll(NOT_PASSED);
    assertThat(passedNames(read(COMPOSE))).containsAll(expected);
  }

  @Test
  void driftIsDetectedInBothDirections() {
    String runbook = "## Configuration reference\n| `A` | 1 | x |\n| `B` | 2 | y |\n## Next\n";
    String compose = "    environment:\n      A: ${A:-1}\n      C: ${C:-3}\n    ports:\n";
    assertThat(documentedNames(runbook)).containsExactly("A", "B");
    assertThat(passedNames(compose)).containsExactly("A", "C");
  }

  @Test
  void dockerComposeConfigValidates() throws Exception {
    Process probe = start("docker", "compose", "version");
    assumeTrue(probe != null && probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0);
    Process p = start("docker", "compose", "-f", COMPOSE.toString(), "config", "--quiet");
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(p.exitValue()).as("docker compose config").isZero();
  }

  private static Process start(String... cmd) {
    try {
      return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    } catch (IOException e) {
      return null;
    }
  }

  private static String read(Path p) throws IOException {
    return Files.readString(p, StandardCharsets.UTF_8);
  }

  private static Set<String> passedNames(String compose) {
    Set<String> names = new LinkedHashSet<>();
    boolean inEnv = false;
    for (String line : compose.split("\n")) {
      if (line.trim().equals("environment:")) {
        inEnv = true;
        continue;
      }
      if (inEnv) {
        Matcher m = ENV_LINE.matcher(line);
        if (m.find()) {
          names.add(m.group(1));
        } else if (!line.isBlank() && !line.trim().startsWith("#")) {
          inEnv = false;
        }
      }
    }
    return names;
  }

  private static Set<String> documentedNames(String runbook) {
    int start = runbook.indexOf("## Configuration reference");
    assertThat(start).as("a '## Configuration reference' section").isNotNegative();
    int end = runbook.indexOf("\n## ", start + 1);
    String section = end < 0 ? runbook.substring(start) : runbook.substring(start, end);
    List<String> lines = new ArrayList<>(List.of(section.split("\n")));
    Set<String> names = new LinkedHashSet<>();
    for (String line : lines) {
      Matcher m = ROW.matcher(line);
      if (m.find()) {
        names.add(m.group(1));
      }
    }
    return names;
  }
}
