package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.FeatureFlagApi.FlagContext;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.FeatureFlagRepository;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository.Setting;
import vn.viettel.okrkpi.config.domain.AnomalyThresholds;
import vn.viettel.okrkpi.config.domain.FeatureFlag;
import vn.viettel.okrkpi.config.domain.FeatureFlagRollout;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ cờ tính năng và cấu hình tenant trên kho trong bộ nhớ (CORE-08-07). */
class FeatureFlagAndSettingServiceTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00Z");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ACTOR = UUID.randomUUID();

  private final List<String> audits = new ArrayList<>();
  private final ConfigAudit audit =
      (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id + ":" + v);

  /** Kho cờ trong bộ nhớ; {@code failNextSave} giả lập ghi đồng thời. */
  static final class Flags implements FeatureFlagRepository {
    final Map<String, FeatureFlag> rows = new HashMap<>();
    boolean failNextSave;
    int reads;

    @Override
    public List<FeatureFlag> findAll(TenantId tenantId) {
      reads++;
      return List.copyOf(rows.values());
    }

    @Override
    public Optional<FeatureFlag> find(TenantId tenantId, String key) {
      return Optional.ofNullable(rows.get(key));
    }

    @Override
    public boolean save(TenantId tenantId, FeatureFlag flag, int expectedVersion, UUID actorId) {
      if (failNextSave) {
        failNextSave = false;
        return false;
      }
      int cur = Optional.ofNullable(rows.get(flag.key())).map(FeatureFlag::rowVersion).orElse(0);
      if (cur != expectedVersion) {
        return false;
      }
      rows.put(flag.key(), flag);
      return true;
    }
  }

  /** Kho cấu hình trong bộ nhớ theo khoá và đơn vị. */
  static final class Settings implements TenantSettingRepository {
    final Map<String, Setting> rows = new HashMap<>();
    boolean failInsert;

    private static String k(String key, UUID unit) {
      return key + "@" + unit;
    }

    @Override
    public Optional<Setting> find(TenantId tenantId, String key, UUID unitId) {
      return Optional.ofNullable(rows.get(k(key, unitId)));
    }

    @Override
    public boolean insert(TenantId tenantId, Setting s, UUID actorId) {
      if (failInsert || rows.containsKey(k(s.key(), s.scopeUnitId()))) {
        return false;
      }
      rows.put(k(s.key(), s.scopeUnitId()), s);
      return true;
    }

    @Override
    public boolean update(TenantId tenantId, Setting s, int expectedVersion, UUID actorId) {
      Setting cur = rows.get(k(s.key(), s.scopeUnitId()));
      if (cur == null || cur.rowVersion() != expectedVersion) {
        return false;
      }
      rows.put(
          k(s.key(), s.scopeUnitId()),
          new Setting(
              cur.id(),
              s.key(),
              s.scopeUnitId(),
              s.value(),
              s.valueSchema(),
              s.description(),
              expectedVersion + 1,
              s.updatedAt()));
      return true;
    }
  }

  private static final TransactionOperations DIRECT = TransactionOperations.withoutTransaction();

  @Test
  void flagServiceMergesCatalogCachesAndInvalidatesOnWrite() {
    Flags repo = new Flags();
    FeatureFlagService service =
        new FeatureFlagService(repo, audit, DIRECT, Clock.fixed(NOW), Duration.ofMinutes(5));
    assertThat(service.list(TENANT)).allMatch(f -> f.rowVersion() == 0);
    FlagContext user = new FlagContext(UUID.randomUUID(), List.of());
    assertThat(service.isEnabled(TENANT, "okr.alignmentGraph", user)).isTrue();
    assertThat(service.isEnabled(TENANT, "okr.aiSuggest", null)).isFalse();
    int reads = repo.reads;
    service.isEnabled(TENANT, "okr.aiSuggest", user);
    assertThat(repo.reads).as("đọc lần hai trúng đệm").isEqualTo(reads);

    FeatureFlag saved =
        service.set(TENANT, ACTOR, "okr.aiSuggest", 0, true, FeatureFlagRollout.ALL, null, null);
    assertThat(saved.rowVersion()).isEqualTo(1);
    assertThat(saved.description()).isNotBlank();
    assertThat(audits).containsExactly("DOI_CO_TINH_NANG:okr.aiSuggest:1");
    assertThat(service.isEnabled(TENANT, "okr.aiSuggest", user)).isTrue();
    assertThat(service.list(TENANT))
        .filteredOn(f -> f.key().equals("okr.aiSuggest"))
        .singleElement()
        .satisfies(f -> assertThat(f.description()).isEqualTo(saved.description()));

    assertThatThrownBy(
            () ->
                service.set(
                    TENANT, ACTOR, "okr.aiSuggest", 0, false, FeatureFlagRollout.ALL, null, null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.set(TENANT, ACTOR, "khong.co", 0, true, null, null, null))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(
            () ->
                service.set(
                    TENANT,
                    ACTOR,
                    "okr.aiSuggest",
                    1,
                    true,
                    FeatureFlagRollout.ALL,
                    NOW,
                    NOW.minusSeconds(1)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
    repo.failNextSave = true;
    assertThatThrownBy(
            () ->
                service.set(
                    TENANT,
                    ACTOR,
                    "okr.aiSuggest",
                    1,
                    true,
                    new FeatureFlagRollout(Set.of(), 10),
                    null,
                    null))
        .isInstanceOf(VersionConflictException.class);
    // Cờ lạ trong kho (đã bỏ khỏi danh mục) bị bỏ qua.
    repo.rows.put(
        "cu.daBo", new FeatureFlag(UUID.randomUUID(), "cu.daBo", null, true, null, null, null, 1));
    service.evict(TENANT);
    assertThat(service.isEnabled(TENANT, "cu.daBo", user)).isFalse();
  }

  @Test
  void settingGetPrefersUnitAndValidatesSchema() {
    Settings repo = new Settings();
    ObjectMapper json = new ObjectMapper();
    TenantSettingService service = new TenantSettingService(repo, audit, json, Clock.fixed(NOW));
    UUID unit = UUID.randomUUID();
    JsonNodeFactory f = JsonNodeFactory.instance;
    put(repo, "csp.frameAncestors", null, f.arrayNode().add("'self'"), "json.string-array");
    put(
        repo,
        "csp.frameAncestors",
        unit,
        f.arrayNode().add("https://a.gov.vn"),
        "json.string-array");
    put(repo, "ui.flag", null, f.booleanNode(true), "json.boolean");
    put(repo, "ui.name", null, f.textNode("x"), "json.string");
    put(repo, "ui.max", null, f.numberNode(3), "json.number");
    put(repo, "ui.obj", null, f.objectNode(), "json.object");
    put(repo, "ui.bad", null, f.textNode("x"), "json.number");
    put(repo, "ui.badArray", null, f.arrayNode().add(1), "json.string-array");
    put(repo, "ui.notArray", null, f.textNode("x"), "json.string-array");
    put(repo, "ui.unknown", null, f.textNode("x"), "khac");
    put(
        repo,
        "ui.badThresholds",
        null,
        f.objectNode().put("signals", "x"),
        "anomaly-thresholds.v1");

    assertThat(service.get(TENANT, "csp.frameAncestors", unit).orElseThrow().get(0).asText())
        .isEqualTo("https://a.gov.vn");
    assertThat(
            service
                .get(TENANT, "csp.frameAncestors", UUID.randomUUID())
                .orElseThrow()
                .get(0)
                .asText())
        .isEqualTo("'self'");
    assertThat(service.get(TENANT, "csp.frameAncestors", null)).isPresent();
    for (String ok : List.of("ui.flag", "ui.name", "ui.max", "ui.obj")) {
      assertThat(service.get(TENANT, ok, null)).as(ok).isPresent();
    }
    for (String bad : List.of("ui.bad", "ui.badArray", "ui.notArray", "ui.unknown", "khong.co")) {
      assertThat(service.get(TENANT, bad, null)).as(bad).isEmpty();
    }
  }

  @Test
  void anomalyThresholdsVersionKeepHistoryAndRejectConflicts() {
    Settings repo = new Settings();
    TenantSettingService service =
        new TenantSettingService(repo, audit, new ObjectMapper(), Clock.fixed(NOW));
    TenantSettingService.Versioned initial = service.anomalyThresholds(TENANT);
    assertThat(initial.rowVersion()).isZero();
    assertThat(initial.thresholds().version()).isEqualTo(1);

    AnomalyThresholds.Signal change =
        new AnomalyThresholds.Signal(
            AnomalyThresholds.SignalCode.CHE_NHO,
            true,
            Map.of("taskIncreasePercent", BigDecimal.valueOf(40)));
    String reason = "Điều chỉnh theo kết quả rà soát quý IV năm 2026";
    TenantSettingService.Versioned v1 =
        service.updateAnomalyThresholds(TENANT, ACTOR, 0, List.of(change), reason);
    assertThat(v1.rowVersion()).isEqualTo(1);
    TenantSettingService.Versioned read = service.anomalyThresholds(TENANT);
    assertThat(read.thresholds().updatedBy()).isEqualTo(ACTOR);
    assertThat(read.thresholds().updatedAt()).isEqualTo(NOW);
    assertThat(read.thresholds().signals()).contains(change);

    TenantSettingService.Versioned v2 =
        service.updateAnomalyThresholds(TENANT, ACTOR, 1, List.of(change), reason);
    assertThat(v2.rowVersion()).isEqualTo(2);
    assertThat(v2.thresholds().version()).isEqualTo(3);
    assertThat(service.get(TENANT, "anomaly.thresholds.v2", null)).isPresent();
    assertThat(audits)
        .containsExactly(
            "DOI_NGUONG_BAT_THUONG:anomaly.thresholds:1",
            "DOI_NGUONG_BAT_THUONG:anomaly.thresholds:2");

    assertThatThrownBy(
            () -> service.updateAnomalyThresholds(TENANT, ACTOR, 1, List.of(change), reason))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> service.updateAnomalyThresholds(TENANT, ACTOR, 2, List.of(change), "ngắn"))
        .isInstanceOf(BusinessException.class);
    // Bản lịch sử cùng phiên bản đã có (ghi đồng thời) thì báo xung đột.
    assertThatThrownBy(
            () -> {
              repo.failInsert = true;
              service.updateAnomalyThresholds(TENANT, ACTOR, 2, List.of(change), reason);
            })
        .isInstanceOf(VersionConflictException.class);
    TenantId other = TenantId.of(UUID.randomUUID());
    Settings fresh = new Settings();
    fresh.failInsert = true;
    TenantSettingService failing =
        new TenantSettingService(fresh, audit, new ObjectMapper(), Clock.fixed(NOW));
    assertThatThrownBy(
            () -> failing.updateAnomalyThresholds(other, ACTOR, 0, List.of(change), reason))
        .isInstanceOf(VersionConflictException.class);
  }

  private static void put(Settings repo, String key, UUID unit, JsonNode value, String schema) {
    assertThat(
            repo.insert(
                TENANT,
                new Setting(UUID.randomUUID(), key, unit, value, schema, null, 1, NOW),
                ACTOR))
        .as(key)
        .isTrue();
  }
}
