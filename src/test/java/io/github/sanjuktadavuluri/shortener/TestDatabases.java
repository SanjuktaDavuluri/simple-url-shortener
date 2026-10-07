package io.github.sanjuktadavuluri.shortener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Fresh SQLite database files for tests, kept under {@code target/} so a clean build removes them.
 */
final class TestDatabases {

  private static final Path ROOT = Path.of("target", "test-databases");

  private TestDatabases() {}

  /** Returns the path of a new, not-yet-existing database file in its own directory. */
  static Path newFile() {
    try {
      Path directory = Files.createDirectories(ROOT.resolve(UUID.randomUUID().toString()));
      return directory.resolve("links.db").toAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
