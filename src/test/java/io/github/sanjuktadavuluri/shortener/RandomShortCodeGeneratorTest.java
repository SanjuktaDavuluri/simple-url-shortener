package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RandomShortCodeGeneratorTest {

  private final ShortCodeGenerator generator = new RandomShortCodeGenerator();

  @Test
  void shortCodesAreSevenBase62Characters() {
    IntStream.range(0, 1_000)
        .mapToObj(i -> generator.next())
        .forEach(code -> assertThat(code).matches("[A-Za-z0-9]{7}"));
  }

  @Test
  void shortCodesVaryBetweenDraws() {
    Set<String> codes = new HashSet<>();
    IntStream.range(0, 1_000).forEach(i -> codes.add(generator.next()));

    assertThat(codes).hasSize(1_000);
  }
}
