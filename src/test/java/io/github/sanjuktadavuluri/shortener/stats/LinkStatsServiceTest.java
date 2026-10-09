package io.github.sanjuktadavuluri.shortener.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.LinkStore;
import io.github.sanjuktadavuluri.shortener.ShortenerProperties;
import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary.ReferrerHostClicks;
import io.github.sanjuktadavuluri.shortener.clicks.DeviceClass;
import io.github.sanjuktadavuluri.shortener.stats.LinkStats.DayClicks;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issues #105 and #107 (spec 0005 seam 4): the Link Stats service with a stand-in Link Store and
 * Click Store. Every failure is the same empty answer, and the Click Store is touched only after
 * the presented token matches the Link's Manage Token hash. The 30-day window is built from the
 * fixed clock in UTC, oldest day first, with days that have no Clicks at zero.
 */
class LinkStatsServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-08T09:30:00.123Z");

  private static final Instant CREATED = Instant.parse("2026-10-01T09:15:02.123Z");

  /** Scripted Manage Tokens: plainly fake, but shaped like real ones (43 characters). */
  private static final String FIRST = "first-scripted-manage-token-for-tests-00001";

  private static final String SECOND = "second-scripted-manage-token-for-tests-0002";

  /** The SHA-256 of each, worked out with {@code shasum -a 256}, not by the code under test. */
  private static final String FIRST_HASH =
      "2f6da021345588db6a010080f5a0b8745ce5054716c32f712a923563d76a623d";

  private static final String SECOND_HASH =
      "018f15577a6280a676334871dd70a5e8986398e7fc2bb0d35e24c4affa2b780f";

  private final StandInLinkStore links = new StandInLinkStore();

  private final StandInClickStore clicks = new StandInClickStore();

  private static final ShortenerProperties PROPERTIES =
      new ShortenerProperties("http://sho.rt", "unused.db", null);

  private final LinkStatsService service =
      new LinkStatsService(links, clicks, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void anUnknownShortCodeGetsNoStatsAndTheClickStoreIsNotCalled() {
    assertThat(service.statsFor("Nope123", FIRST)).isEmpty();

    assertThat(clicks.summarised).isEmpty();
  }

  @Test
  void aLinkWithoutAManageTokenHashGetsNoStatsForAnyTokenAndTheClickStoreIsNotCalled() {
    links.add("Old1234", Optional.empty());

    assertThat(service.statsFor("Old1234", FIRST)).isEmpty();
    assertThat(service.statsFor("Old1234", "")).isEmpty();

    assertThat(clicks.summarised).isEmpty();
  }

  @Test
  void aWrongTokenGetsNoStatsAndTheClickStoreIsNotCalled() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));

    assertThat(service.statsFor("Ab3xK9q", FIRST + "x")).isEmpty();
    assertThat(service.statsFor("Ab3xK9q", "")).isEmpty();
    assertThat(service.statsFor("Ab3xK9q", FIRST_HASH)).isEmpty();

    assertThat(clicks.summarised).isEmpty();
  }

  @Test
  void anotherLinksTokenGetsNoStatsAndTheClickStoreIsNotCalled() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    links.add("Zz9yX8w", Optional.of(SECOND_HASH));

    assertThat(service.statsFor("Ab3xK9q", SECOND)).isEmpty();

    assertThat(clicks.summarised).isEmpty();
  }

  @Test
  void theRightTokenGetsTheLinksStatsWithBotsOutOfTheHeadlineClickCount() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    Instant lastHumanClick = Instant.parse("2026-10-08T08:00:00.004Z");
    clicks.summaries.put(
        "Ab3xK9q",
        summary(
            Map.of(AgentCategory.BROWSER, 38L, AgentCategory.OTHER, 4L, AgentCategory.BOT, 7L),
            Map.of(),
            Optional.of(lastHumanClick)));

    Optional<LinkStats> stats = service.statsFor("Ab3xK9q", FIRST);

    assertThat(stats)
        .hasValueSatisfying(
            it -> {
              assertThat(it.shortCode()).isEqualTo("Ab3xK9q");
              assertThat(it.shortUrl()).isEqualTo("http://sho.rt/Ab3xK9q");
              assertThat(it.longUrl()).isEqualTo("https://example.com/Ab3xK9q");
              assertThat(it.createdAt()).isEqualTo(CREATED);
              assertThat(it.generatedAt()).isEqualTo(NOW);
              assertThat(it.headlineClicks()).isEqualTo(42);
              assertThat(it.botClicks()).isEqualTo(7);
              assertThat(it.lastClickAt()).contains(lastHumanClick);
              assertThat(it.byAgentCategory())
                  .containsExactlyInAnyOrderEntriesOf(
                      Map.of(
                          AgentCategory.BROWSER, 38L,
                          AgentCategory.OTHER, 4L,
                          AgentCategory.BOT, 7L));
            });
    assertThat(clicks.summarised).containsExactly("Ab3xK9q");
  }

  @Test
  void aLinkWithNoClicksGetsZerosForEveryAgentCategoryAndNoLastClick() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));

    assertThat(service.statsFor("Ab3xK9q", FIRST))
        .hasValueSatisfying(
            it -> {
              assertThat(it.headlineClicks()).isZero();
              assertThat(it.botClicks()).isZero();
              assertThat(it.lastClickAt()).isEmpty();
              assertThat(it.byAgentCategory())
                  .containsExactlyInAnyOrderEntriesOf(
                      Map.of(
                          AgentCategory.BROWSER, 0L,
                          AgentCategory.OTHER, 0L,
                          AgentCategory.BOT, 0L));
            });
  }

  @Test
  void theClickWindowStartsAtMidnightUtc29DaysBeforeToday() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));

    assertThat(service.statsFor("Ab3xK9q", FIRST)).isPresent();

    assertThat(clicks.windowStarts).containsExactly(Instant.parse("2026-09-09T00:00:00Z"));
  }

  @Test
  void theClicksPerDayAreExactlyThirtyUtcDaysEndingTodayOldestFirstWithEmptyDaysAtZero() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    clicks.summaries.put(
        "Ab3xK9q",
        summary(
            Map.of(AgentCategory.BROWSER, 11L),
            Map.of(
                LocalDate.parse("2026-09-09"), 4L,
                LocalDate.parse("2026-10-01"), 2L,
                LocalDate.parse("2026-10-08"), 5L),
            Optional.of(NOW)));

    assertThat(service.statsFor("Ab3xK9q", FIRST))
        .hasValueSatisfying(
            it ->
                assertThat(it.clicksPerDay())
                    .containsExactly(
                        day("2026-09-09", 4),
                        day("2026-09-10", 0),
                        day("2026-09-11", 0),
                        day("2026-09-12", 0),
                        day("2026-09-13", 0),
                        day("2026-09-14", 0),
                        day("2026-09-15", 0),
                        day("2026-09-16", 0),
                        day("2026-09-17", 0),
                        day("2026-09-18", 0),
                        day("2026-09-19", 0),
                        day("2026-09-20", 0),
                        day("2026-09-21", 0),
                        day("2026-09-22", 0),
                        day("2026-09-23", 0),
                        day("2026-09-24", 0),
                        day("2026-09-25", 0),
                        day("2026-09-26", 0),
                        day("2026-09-27", 0),
                        day("2026-09-28", 0),
                        day("2026-09-29", 0),
                        day("2026-09-30", 0),
                        day("2026-10-01", 2),
                        day("2026-10-02", 0),
                        day("2026-10-03", 0),
                        day("2026-10-04", 0),
                        day("2026-10-05", 0),
                        day("2026-10-06", 0),
                        day("2026-10-07", 0),
                        day("2026-10-08", 5)));
  }

  @Test
  void aClickJustBeforeUtcMidnightIsOnTheLastDayUntilMidnightThenMovesBackOneDay() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    clicks.summaries.put(
        "Ab3xK9q",
        summary(
            Map.of(AgentCategory.BROWSER, 1L),
            Map.of(LocalDate.parse("2026-10-08"), 1L),
            Optional.of(Instant.parse("2026-10-08T23:59:59.998Z"))));

    LinkStats justBefore =
        serviceAt(Instant.parse("2026-10-08T23:59:59.999Z")).statsFor("Ab3xK9q", FIRST).get();
    LinkStats justAfter =
        serviceAt(Instant.parse("2026-10-09T00:00:00.000Z")).statsFor("Ab3xK9q", FIRST).get();

    assertThat(justBefore.clicksPerDay()).hasSize(30);
    assertThat(justBefore.clicksPerDay().getFirst()).isEqualTo(day("2026-09-09", 0));
    assertThat(justBefore.clicksPerDay().getLast()).isEqualTo(day("2026-10-08", 1));
    assertThat(justAfter.clicksPerDay()).hasSize(30);
    assertThat(justAfter.clicksPerDay().getFirst()).isEqualTo(day("2026-09-10", 0));
    assertThat(justAfter.clicksPerDay().get(28)).isEqualTo(day("2026-10-08", 1));
    assertThat(justAfter.clicksPerDay().getLast()).isEqualTo(day("2026-10-09", 0));
    assertThat(clicks.windowStarts)
        .containsExactly(
            Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"));
  }

  @Test
  void theDaysAreUtcDaysWhateverTheClocksTimeZone() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    // 23:30 UTC on 8 October is already 9 October in Tokyo; it is still 8 October in UTC.
    Instant lateEvening = Instant.parse("2026-10-08T23:30:00Z");

    for (ZoneId zone : List.of(ZoneId.of("Asia/Tokyo"), ZoneId.of("America/Chicago"))) {
      LinkStats stats =
          new LinkStatsService(links, clicks, PROPERTIES, Clock.fixed(lateEvening, zone))
              .statsFor("Ab3xK9q", FIRST)
              .get();

      assertThat(stats.clicksPerDay().getFirst()).as(zone.getId()).isEqualTo(day("2026-09-09", 0));
      assertThat(stats.clicksPerDay().getLast()).as(zone.getId()).isEqualTo(day("2026-10-08", 0));
    }
    assertThat(clicks.windowStarts).containsOnly(Instant.parse("2026-09-09T00:00:00Z"));
  }

  @Test
  void theDeviceClassSplitAndReferrerHostsComeFromTheClickSummary() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));
    clicks.summaries.put(
        "Ab3xK9q",
        new ClickSummary(
            Map.of(AgentCategory.BROWSER, 38L, AgentCategory.OTHER, 4L, AgentCategory.BOT, 7L),
            Map.of(DeviceClass.DESKTOP, 30L, DeviceClass.MOBILE, 12L),
            List.of(
                new ReferrerHostClicks("news.example.com", 20), new ReferrerHostClicks("t.co", 9)),
            13,
            Map.of(),
            Optional.of(NOW)));

    assertThat(service.statsFor("Ab3xK9q", FIRST))
        .hasValueSatisfying(
            it -> {
              assertThat(it.byDeviceClass())
                  .containsExactlyInAnyOrderEntriesOf(
                      Map.of(DeviceClass.DESKTOP, 30L, DeviceClass.MOBILE, 12L));
              assertThat(it.topReferrerHosts())
                  .containsExactly(
                      new ReferrerHostClicks("news.example.com", 20),
                      new ReferrerHostClicks("t.co", 9));
              assertThat(it.noReferrerHost()).isEqualTo(13);
            });
  }

  @Test
  void aLinkWithNoClicksGetsThirtyZeroDaysBothDeviceClassesAtZeroAndNoReferrerHosts() {
    links.add("Ab3xK9q", Optional.of(FIRST_HASH));

    assertThat(service.statsFor("Ab3xK9q", FIRST))
        .hasValueSatisfying(
            it -> {
              assertThat(it.clicksPerDay())
                  .hasSize(30)
                  .allSatisfy(day -> assertThat(day.clicks()).isZero());
              assertThat(it.clicksPerDay().getFirst()).isEqualTo(day("2026-09-09", 0));
              assertThat(it.clicksPerDay().getLast()).isEqualTo(day("2026-10-08", 0));
              assertThat(it.byDeviceClass())
                  .containsExactlyInAnyOrderEntriesOf(
                      Map.of(DeviceClass.DESKTOP, 0L, DeviceClass.MOBILE, 0L));
              assertThat(it.topReferrerHosts()).isEmpty();
              assertThat(it.noReferrerHost()).isZero();
            });
  }

  private LinkStatsService serviceAt(Instant now) {
    return new LinkStatsService(links, clicks, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static DayClicks day(String date, long clicks) {
    return new DayClicks(LocalDate.parse(date), clicks);
  }

  /** A Click Summary with these Agent Category and per-day counts, and nothing else. */
  private static ClickSummary summary(
      Map<AgentCategory, Long> byAgentCategory,
      Map<LocalDate, Long> clicksPerDay,
      Optional<Instant> lastClickAt) {
    return new ClickSummary(byAgentCategory, Map.of(), List.of(), 0, clicksPerDay, lastClickAt);
  }

  /** Holds Links in memory; only {@code findForStats} is used by the service. */
  private static final class StandInLinkStore implements LinkStore {

    private final Map<String, StoredLink> stored = new HashMap<>();

    void add(String shortCode, Optional<String> manageTokenHash) {
      stored.put(
          shortCode,
          new StoredLink(shortCode, "https://example.com/" + shortCode, CREATED, manageTokenHash));
    }

    @Override
    public Optional<StoredLink> findForStats(String shortCode) {
      return Optional.ofNullable(stored.get(shortCode));
    }

    @Override
    public void save(
        String shortCode, String longUrl, String manageTokenHash, Optional<Instant> expiry) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Destination> findDestination(String shortCode) {
      throw new UnsupportedOperationException();
    }
  }

  /** Answers {@code summarise} with a canned Click Summary and records every call. */
  private static final class StandInClickStore implements ClickStore {

    final Map<String, ClickSummary> summaries = new HashMap<>();

    final List<String> summarised = new ArrayList<>();

    final List<Instant> windowStarts = new ArrayList<>();

    @Override
    public ClickSummary summarise(String shortCode, Instant windowStart) {
      summarised.add(shortCode);
      windowStarts.add(windowStart);
      return summaries.getOrDefault(shortCode, ClickSummary.none());
    }

    @Override
    public void saveAll(List<Click> clicks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Click> listClicks(String shortCode) {
      throw new UnsupportedOperationException();
    }
  }
}
