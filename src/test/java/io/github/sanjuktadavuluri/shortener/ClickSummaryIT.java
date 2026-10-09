package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #105 (spec 0005, seam 3): the Click Store summarises one Link's stored Clicks for its
 * Stats. Clicks are hand-built and saved through {@code saveAll}; the summary is read back through
 * {@code summarise}, never by reading the table.
 */
class ClickSummaryIT extends IntegrationTest {

  private static final Instant WINDOW_START = Instant.parse("2026-09-09T00:00:00Z");

  @Autowired private ClickStore clickStore;

  @Test
  void theSummaryCountsALinksClicksInEachAgentCategory() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", "2026-10-01T10:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-02T10:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-03T10:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-04T10:00:00.000Z", AgentCategory.OTHER),
            click("Ab3xK9q", "2026-10-05T10:00:00.000Z", AgentCategory.BOT),
            click("Ab3xK9q", "2026-10-05T11:00:00.000Z", AgentCategory.BOT),
            click("Zz9yX8w", "2026-10-06T10:00:00.000Z", AgentCategory.BROWSER)));

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).byAgentCategory())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(AgentCategory.BROWSER, 3L, AgentCategory.OTHER, 1L, AgentCategory.BOT, 2L));
  }

  @Test
  void theLastClickIsTheLatestNonBotClickEvenWhenABotClickedLater() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", "2026-10-01T10:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-07T08:15:30.004Z", AgentCategory.OTHER),
            click("Ab3xK9q", "2026-10-03T10:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-08T09:00:00.000Z", AgentCategory.BOT),
            click("Zz9yX8w", "2026-10-08T09:10:00.000Z", AgentCategory.BROWSER)));

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).lastClickAt())
        .contains(Instant.parse("2026-10-07T08:15:30.004Z"));
  }

  @Test
  void aLinkWithOnlyBotClicksHasNoLastClick() {
    clickStore.saveAll(List.of(click("Ab3xK9q", "2026-10-01T10:00:00.000Z", AgentCategory.BOT)));

    ClickSummary summary = clickStore.summarise("Ab3xK9q", WINDOW_START);

    assertThat(summary.lastClickAt()).isEmpty();
    assertThat(summary.clicks(AgentCategory.BOT)).isEqualTo(1);
  }

  @Test
  void anUnknownShortCodeHasAnEmptySummary() {
    clickStore.saveAll(
        List.of(click("Ab3xK9q", "2026-10-01T10:00:00.000Z", AgentCategory.BROWSER)));

    assertThat(clickStore.summarise("Nope123", WINDOW_START))
        .isEqualTo(
            new ClickSummary(
                Map.of(AgentCategory.BROWSER, 0L, AgentCategory.OTHER, 0L, AgentCategory.BOT, 0L),
                Optional.empty()));
  }

  private static Click click(String shortCode, String clickedAt, AgentCategory agentCategory) {
    return new Click(
        shortCode,
        Instant.parse(clickedAt),
        Optional.of("news.example.com"),
        agentCategory,
        DeviceClass.DESKTOP);
  }
}
