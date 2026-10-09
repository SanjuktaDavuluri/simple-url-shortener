package io.github.sanjuktadavuluri.shortener.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanjuktadavuluri.shortener.LinkStore;
import io.github.sanjuktadavuluri.shortener.ShortenerProperties;
import io.github.sanjuktadavuluri.shortener.clicks.AgentCategory;
import io.github.sanjuktadavuluri.shortener.clicks.Click;
import io.github.sanjuktadavuluri.shortener.clicks.ClickStore;
import io.github.sanjuktadavuluri.shortener.clicks.ClickSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #105 (spec 0005 seam 4): the Link Stats service with a stand-in Link Store and Click Store.
 * Every failure is the same empty answer, and the Click Store is touched only after the presented
 * token matches the Link's Manage Token hash.
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

  private final LinkStatsService service =
      new LinkStatsService(
          links,
          clicks,
          new ShortenerProperties("http://sho.rt", "unused.db", null),
          Clock.fixed(NOW, ZoneOffset.UTC));

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
        new ClickSummary(
            Map.of(AgentCategory.BROWSER, 38L, AgentCategory.OTHER, 4L, AgentCategory.BOT, 7L),
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
      return summaries.getOrDefault(shortCode, new ClickSummary(Map.of(), Optional.empty()));
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
