package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Cờ tính năng, danh mục mặc định và ngưỡng bất thường (CORE-08-07). */
class FeatureFlagTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00Z");

  @Test
  void catalogCopyMatchesSeedFile() throws IOException {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("data/seed/feature-flags"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("không tìm thấy data/seed/feature-flags").isNotNull();
    Path seed = dir.resolve("data/seed/feature-flags/feature-flags.csv");
    try (InputStream in = FeatureFlagCatalog.class.getResourceAsStream(FeatureFlagCatalog.FILE)) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo(Files.readString(seed));
    }
    assertThat(FeatureFlagCatalog.all()).isNotEmpty();
    assertThat(FeatureFlagCatalog.all())
        .extracting(FeatureFlag::key)
        .isSorted()
        .contains("ai.weeklySummary", "ui.elderlyModeDefault");
    assertThat(FeatureFlagCatalog.defaultOf("ai.weeklySummary").orElseThrow().stored()).isFalse();
    assertThat(FeatureFlagCatalog.defaultOf("khong.co")).isEmpty();
  }

  @Test
  void unitRolloutAdmitsDescendantsOnly() {
    UUID unit = UUID.randomUUID();
    UUID child = UUID.randomUUID();
    FeatureFlag flag =
        new FeatureFlag(
            null,
            "okr.aiSuggest",
            null,
            true,
            new FeatureFlagRollout(Set.of(unit), 100),
            null,
            null,
            0);
    assertThat(flag.isOn(NOW, UUID.randomUUID(), List.of(child, unit))).isTrue();
    assertThat(flag.isOn(NOW, UUID.randomUUID(), List.of(UUID.randomUUID()))).isFalse();
    assertThat(flag.isOn(NOW, UUID.randomUUID(), null)).isFalse();
  }

  @Test
  void percentRolloutIsStablePerUserAndRoughlyProportional() {
    FeatureFlagRollout half = new FeatureFlagRollout(Set.of(), 50);
    UUID user = UUID.randomUUID();
    boolean first = half.admits("task.offlineSync", user, List.of());
    IntStream.range(0, 20)
        .forEach(
            i -> assertThat(half.admits("task.offlineSync", user, List.of())).isEqualTo(first));
    long admitted =
        IntStream.range(0, 2000)
            .filter(i -> half.admits("task.offlineSync", UUID.randomUUID(), List.of()))
            .count();
    assertThat(admitted).isBetween(850L, 1150L);
    assertThat(new FeatureFlagRollout(Set.of(), 0).admits("x", user, List.of())).isFalse();
    assertThatThrownBy(() -> new FeatureFlagRollout(Set.of(), 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validityWindowAndDisabledFlag() {
    FeatureFlag flag =
        new FeatureFlag(
            UUID.randomUUID(),
            "okr.alignmentGraph",
            null,
            true,
            FeatureFlagRollout.ALL,
            NOW,
            NOW.plusSeconds(60),
            3);
    assertThat(flag.isOn(NOW.minusSeconds(1), UUID.randomUUID(), List.of())).isFalse();
    assertThat(flag.isOn(NOW, UUID.randomUUID(), List.of())).isTrue();
    assertThat(flag.isOn(NOW.plusSeconds(60), UUID.randomUUID(), List.of())).isFalse();
    FeatureFlag off = flag.changed(UUID.randomUUID(), false, FeatureFlagRollout.ALL, null, null);
    assertThat(off.id()).isEqualTo(flag.id());
    assertThat(off.rowVersion()).isEqualTo(4);
    assertThat(off.isOn(NOW, UUID.randomUUID(), List.of())).isFalse();
    assertThatThrownBy(() -> new FeatureFlag(null, "k", null, true, null, NOW, NOW, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anomalyThresholdsMergeByCodeAndValidateReason() {
    AnomalyThresholds base = AnomalyThresholds.defaults();
    assertThat(base.signals()).hasSize(AnomalyThresholds.SignalCode.values().length);
    UUID by = UUID.randomUUID();
    AnomalyThresholds.Signal change =
        new AnomalyThresholds.Signal(
            AnomalyThresholds.SignalCode.CAO_BANG,
            false,
            Map.of("sameLevelPercent", BigDecimal.valueOf(85)));
    AnomalyThresholds next =
        base.next(List.of(change), "Điều chỉnh theo kết quả rà soát quý IV", by, NOW);
    assertThat(next.version()).isEqualTo(2);
    assertThat(next.updatedBy()).isEqualTo(by);
    assertThat(next.signals()).hasSize(base.signals().size()).contains(change);
    assertThatThrownBy(() -> base.next(List.of(change), "ngắn", by, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                base.next(
                    List.of(change, change), "Điều chỉnh theo kết quả rà soát quý IV", by, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
