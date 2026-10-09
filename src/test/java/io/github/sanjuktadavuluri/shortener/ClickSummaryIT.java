package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary.ReferrerHostClicks;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issues #105 and #107 (spec 0005, seam 3): the Click Store summarises one Link's stored Clicks for
 * its Stats. Clicks are hand-built and saved through {@code saveAll}; the summary is read back
 * through {@code summarise}, never by reading the table. Bot Clicks count only in the Agent
 * Category split (ADR 0013).
 */
class ClickSummaryIT extends IntegrationTest {

  private static final Instant WINDOW_START = Instant.parse("2026-09-09T00:00:00Z");

  private static final Optional<String> NO_REFERRER_HOST = Optional.empty();

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
  void theDeviceClassSplitCountsNonBotClicksOnly() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, "news.example.com"),
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, "news.example.com"),
            click("Ab3xK9q", AgentCategory.OTHER, DeviceClass.DESKTOP, null),
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.MOBILE, "t.co"),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.MOBILE, "t.co"),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.MOBILE, null),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.DESKTOP, null),
            click("Zz9yX8w", AgentCategory.BROWSER, DeviceClass.MOBILE, "t.co")));

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).byDeviceClass())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(DeviceClass.DESKTOP, 3L, DeviceClass.MOBILE, 1L));
  }

  @Test
  void theTopReferrerHostsAreTheTenWithTheMostNonBotClicksMostFirstAndAnEleventhIsCut() {
    List<Click> clicks = new ArrayList<>();
    String[] hosts = {
      "h01.example",
      "h02.example",
      "h03.example",
      "h04.example",
      "h05.example",
      "h06.example",
      "h07.example",
      "h08.example",
      "h09.example",
      "h10.example",
      "h11.example"
    };
    // h01.example gets 1 Click, h02.example 2, … h11.example 11.
    for (int i = 0; i < hosts.length; i++) {
      for (int n = 0; n <= i; n++) {
        clicks.add(click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, hosts[i]));
      }
    }
    // Twelve bot Clicks from h01.example would put it first if bots counted.
    for (int n = 0; n < 12; n++) {
      clicks.add(click("Ab3xK9q", AgentCategory.BOT, DeviceClass.DESKTOP, "h01.example"));
    }
    clicks.add(click("Zz9yX8w", AgentCategory.BROWSER, DeviceClass.DESKTOP, "other.example"));
    clickStore.saveAll(clicks);

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).topReferrerHosts())
        .containsExactly(
            new ReferrerHostClicks("h11.example", 11),
            new ReferrerHostClicks("h10.example", 10),
            new ReferrerHostClicks("h09.example", 9),
            new ReferrerHostClicks("h08.example", 8),
            new ReferrerHostClicks("h07.example", 7),
            new ReferrerHostClicks("h06.example", 6),
            new ReferrerHostClicks("h05.example", 5),
            new ReferrerHostClicks("h04.example", 4),
            new ReferrerHostClicks("h03.example", 3),
            new ReferrerHostClicks("h02.example", 2));
  }

  @Test
  void referrerHostsWithTheSameCountAreOrderedByHostAndTheCutKeepsTheFirstTen() {
    List<Click> clicks = new ArrayList<>();
    clicks.add(click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, "zz.example"));
    clicks.add(click("Ab3xK9q", AgentCategory.OTHER, DeviceClass.DESKTOP, "zz.example"));
    for (String host :
        List.of(
            "k.example",
            "j.example",
            "i.example",
            "h.example",
            "g.example",
            "f.example",
            "e.example",
            "d.example",
            "c.example",
            "b.example",
            "a.example")) {
      clicks.add(click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.MOBILE, host));
    }
    clickStore.saveAll(clicks);

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).topReferrerHosts())
        .containsExactly(
            new ReferrerHostClicks("zz.example", 2),
            new ReferrerHostClicks("a.example", 1),
            new ReferrerHostClicks("b.example", 1),
            new ReferrerHostClicks("c.example", 1),
            new ReferrerHostClicks("d.example", 1),
            new ReferrerHostClicks("e.example", 1),
            new ReferrerHostClicks("f.example", 1),
            new ReferrerHostClicks("g.example", 1),
            new ReferrerHostClicks("h.example", 1),
            new ReferrerHostClicks("i.example", 1));
  }

  @Test
  void theNoReferrerHostCountIsTheNonBotClicksWithoutAReferrerHost() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, null),
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.MOBILE, null),
            click("Ab3xK9q", AgentCategory.OTHER, DeviceClass.DESKTOP, null),
            click("Ab3xK9q", AgentCategory.BROWSER, DeviceClass.DESKTOP, "t.co"),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.DESKTOP, null),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.DESKTOP, null),
            click("Zz9yX8w", AgentCategory.BROWSER, DeviceClass.DESKTOP, null)));

    ClickSummary summary = clickStore.summarise("Ab3xK9q", WINDOW_START);

    assertThat(summary.noReferrerHost()).isEqualTo(3);
    assertThat(summary.topReferrerHosts()).containsExactly(new ReferrerHostClicks("t.co", 1));
  }

  @Test
  void theClicksPerDayAreNonBotClicksPerUtcDayFromTheWindowStart() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", "2026-09-08T23:59:59.999Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-09-09T00:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-09-09T23:59:59.999Z", AgentCategory.OTHER),
            click("Ab3xK9q", "2026-09-10T00:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-01T12:00:00.000Z", AgentCategory.BOT),
            click("Ab3xK9q", "2026-10-08T09:00:00.000Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-08T09:29:59.999Z", AgentCategory.OTHER),
            click("Zz9yX8w", "2026-10-08T09:00:00.000Z", AgentCategory.BROWSER)));

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START).clicksPerDay())
        .containsExactly(
            entry(LocalDate.parse("2026-09-09"), 2L),
            entry(LocalDate.parse("2026-09-10"), 1L),
            entry(LocalDate.parse("2026-10-08"), 2L));
  }

  @Test
  void theClicksPerDayStartAtWhicheverWindowStartIsGiven() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", "2026-10-06T23:59:59.999Z", AgentCategory.BROWSER),
            click("Ab3xK9q", "2026-10-07T00:00:00.000Z", AgentCategory.BROWSER)));

    assertThat(
            clickStore.summarise("Ab3xK9q", Instant.parse("2026-10-07T00:00:00Z")).clicksPerDay())
        .containsExactly(entry(LocalDate.parse("2026-10-07"), 1L));
  }

  @Test
  void botClicksAreLeftOutOfEveryBreakdownButTheAgentCategorySplit() {
    clickStore.saveAll(
        List.of(
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.MOBILE, "t.co"),
            click("Ab3xK9q", AgentCategory.BOT, DeviceClass.DESKTOP, null)));

    assertThat(clickStore.summarise("Ab3xK9q", WINDOW_START))
        .isEqualTo(
            new ClickSummary(
                Map.of(AgentCategory.BROWSER, 0L, AgentCategory.OTHER, 0L, AgentCategory.BOT, 2L),
                Map.of(DeviceClass.DESKTOP, 0L, DeviceClass.MOBILE, 0L),
                List.of(),
                0,
                Map.of(),
                Optional.empty()));
  }

  @Test
  void anUnknownShortCodeHasAnEmptySummary() {
    clickStore.saveAll(
        List.of(click("Ab3xK9q", "2026-10-01T10:00:00.000Z", AgentCategory.BROWSER)));

    assertThat(clickStore.summarise("Nope123", WINDOW_START))
        .isEqualTo(
            new ClickSummary(
                Map.of(AgentCategory.BROWSER, 0L, AgentCategory.OTHER, 0L, AgentCategory.BOT, 0L),
                Map.of(DeviceClass.DESKTOP, 0L, DeviceClass.MOBILE, 0L),
                List.of(),
                0,
                Map.of(),
                Optional.empty()))
        .isEqualTo(ClickSummary.none());
  }

  private static Click click(String shortCode, String clickedAt, AgentCategory agentCategory) {
    return new Click(
        shortCode,
        Instant.parse(clickedAt),
        Optional.of("news.example.com"),
        agentCategory,
        DeviceClass.DESKTOP);
  }

  /** A Click inside the window (yesterday, UTC) from this kind of client and Referrer Host. */
  private static Click click(
      String shortCode, AgentCategory agentCategory, DeviceClass deviceClass, String host) {
    return new Click(
        shortCode,
        NOW.minus(Duration.ofDays(1)),
        host == null ? NO_REFERRER_HOST : Optional.of(host),
        agentCategory,
        deviceClass);
  }
}
