package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository.Setting;
import vn.viettel.okrkpi.config.domain.AnomalyThresholds;
import vn.viettel.okrkpi.config.domain.RetentionClass;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cấu hình theo tenant của MOD-08 (CORE-08-07): cổng {@link TenantSettingApi} và ngưỡng phát hiện
 * bất thường (QT-14, SCR-1401, BR-14-09). Ngưỡng hiện hành ở khoá {@value #ANOMALY_KEY}; mỗi lần
 * lưu, phiên bản cũ được chép sang khoá {@code anomaly.thresholds.v<n>} trên cùng bảng (suy luận,
 * vì khoá duy nhất {@code (tenant_id, setting_key, scope_unit_id)} chỉ cho một dòng mỗi khoá).
 */
public class TenantSettingService implements TenantSettingApi {

  static final String TX = "configTransactionManager";
  public static final String ANOMALY_KEY = "anomaly.thresholds";
  public static final String ANOMALY_SCHEMA = "anomaly-thresholds.v1";
  static final String AUDIT_ACTION = "DOI_NGUONG_BAT_THUONG";
  static final String OBJECT_TYPE = "config.tenant_setting";

  /**
   * DL-13: lược đồ giá trị của khoá {@code retention.<LỚP>}: {@code {"hot":"P5Y","total":"P20Y"}}.
   */
  public static final String RETENTION_SCHEMA = "retention-class.v1";

  private static final Logger log = LoggerFactory.getLogger(TenantSettingService.class);

  private final TenantSettingRepository settings;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final Clock clock;

  public TenantSettingService(
      TenantSettingRepository settings, ConfigAudit audit, ObjectMapper json, Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<JsonNode> get(TenantId tenantId, String key, UUID unitId) {
    Optional<Setting> s = unitId == null ? Optional.empty() : settings.find(tenantId, key, unitId);
    if (s.isEmpty()) {
      s = settings.find(tenantId, key, null);
    }
    return s.filter(this::valid).map(Setting::value);
  }

  /** Ngưỡng hiện hành kèm phiên bản dòng; tenant chưa lưu dùng ngưỡng mặc định, phiên bản 0. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Versioned anomalyThresholds(TenantId tenantId) {
    return settings
        .find(tenantId, ANOMALY_KEY, null)
        .map(s -> new Versioned(read(s.value()), s.rowVersion()))
        .orElseGet(() -> new Versioned(AnomalyThresholds.defaults(), 0));
  }

  /**
   * Lưu phiên bản ngưỡng mới có lý do (BR-14-09); phiên bản cũ được giữ.
   *
   * @param expectedVersion phiên bản dòng trong {@code If-Match}
   */
  @Transactional(TX)
  public Versioned updateAnomalyThresholds(
      TenantId tenantId,
      UUID actorId,
      long expectedVersion,
      List<AnomalyThresholds.Signal> changes,
      String reason) {
    Optional<Setting> row = settings.find(tenantId, ANOMALY_KEY, null);
    int current = row.map(Setting::rowVersion).orElse(0);
    if (expectedVersion != current) {
      throw VersionConflictException.of("CONFIG", expectedVersion, current);
    }
    AnomalyThresholds old = row.map(s -> read(s.value())).orElseGet(AnomalyThresholds::defaults);
    AnomalyThresholds next;
    try {
      next = old.next(changes, reason, actorId, clock.instant());
    } catch (IllegalArgumentException e) {
      throw new BusinessException("COMMON-422-VALIDATION", e.getMessage());
    }
    Instant now = clock.instant();
    UUID id;
    if (row.isEmpty()) {
      id = Ids.newUuidV7();
      boolean inserted =
          settings.insert(
              tenantId,
              new Setting(id, ANOMALY_KEY, null, write(next), ANOMALY_SCHEMA, null, 1, now),
              actorId);
      if (!inserted) {
        throw VersionConflictException.of("CONFIG", expectedVersion, 1);
      }
    } else {
      Setting s = row.get();
      id = s.id();
      boolean archived =
          settings.insert(
              tenantId,
              new Setting(
                  Ids.newUuidV7(),
                  ANOMALY_KEY + ".v" + old.version(),
                  null,
                  s.value(),
                  ANOMALY_SCHEMA,
                  "Phiên bản ngưỡng đã thay",
                  0,
                  now),
              actorId);
      if (!archived
          || !settings.update(
              tenantId,
              new Setting(id, ANOMALY_KEY, null, write(next), ANOMALY_SCHEMA, null, current, now),
              current,
              actorId)) {
        throw VersionConflictException.of("CONFIG", expectedVersion, current + 1);
      }
    }
    // Ngưỡng mặc định chưa lưu có phiên bản 0, nên dòng đầu tiên mang phiên bản 1.
    int version = current + 1;
    audit.changed(tenantId, actorId, AUDIT_ACTION, OBJECT_TYPE, ANOMALY_KEY, id, version);
    return new Versioned(next, version);
  }

  /**
   * Thời hạn lưu giữ hiệu lực của bảy lớp (DL-13, TL-03 chương 16). Lớp chưa cấu hình, hoặc cấu
   * hình sai lược đồ hay thấp hơn mức sàn, dùng giá trị đề xuất của TL-03 và đánh dấu {@code
   * proposed}.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Map<RetentionClass, RetentionClass.Rule> retention(TenantId tenantId) {
    Map<RetentionClass, RetentionClass.Rule> out = new EnumMap<>(RetentionClass.class);
    for (RetentionClass c : RetentionClass.values()) {
      out.put(
          c,
          settings
              .find(tenantId, c.settingKey(), null)
              .filter(this::valid)
              .map(s -> readRetention(c, s.value()))
              .orElseGet(c::defaults));
    }
    return out;
  }

  private static RetentionClass.Rule readRetention(RetentionClass c, JsonNode v) {
    return new RetentionClass.Rule(
        c, Period.parse(v.path("hot").asText()), Period.parse(v.path("total").asText()), false);
  }

  private static boolean validRetention(String key, JsonNode v) {
    if (!key.startsWith("retention.") || !v.isObject()) {
      return false;
    }
    try {
      readRetention(RetentionClass.valueOf(key.substring("retention.".length())), v);
      return true;
    } catch (IllegalArgumentException | DateTimeParseException e) {
      return false;
    }
  }

  /** Ngưỡng và phiên bản dòng ({@code ETag}). */
  public record Versioned(AnomalyThresholds thresholds, int rowVersion) {}

  private boolean valid(Setting s) {
    boolean ok =
        switch (s.valueSchema()) {
          case "json.boolean" -> s.value().isBoolean();
          case "json.string" -> s.value().isTextual();
          case "json.number" -> s.value().isNumber();
          case "json.object" -> s.value().isObject();
          case "json.string-array" -> isStringArray(s.value());
          case RETENTION_SCHEMA -> validRetention(s.key(), s.value());
          case ANOMALY_SCHEMA -> {
            try {
              read(s.value());
              yield true;
            } catch (RuntimeException e) {
              yield false;
            }
          }
          default -> false;
        };
    if (!ok) {
      log.warn("Bỏ qua cấu hình {} vì giá trị không khớp lược đồ {}", s.key(), s.valueSchema());
    }
    return ok;
  }

  private static boolean isStringArray(JsonNode v) {
    if (!v.isArray()) {
      return false;
    }
    for (JsonNode n : v) {
      if (!n.isTextual()) {
        return false;
      }
    }
    return true;
  }

  JsonNode write(AnomalyThresholds t) {
    ObjectNode root = json.createObjectNode();
    root.put("version", t.version());
    root.put("reason", t.reason());
    if (t.updatedAt() != null) {
      root.put("updatedAt", t.updatedAt().toString());
    }
    if (t.updatedBy() != null) {
      root.put("updatedBy", t.updatedBy().toString());
    }
    ArrayNode signals = root.putArray("signals");
    for (AnomalyThresholds.Signal s : t.signals()) {
      ObjectNode n = signals.addObject();
      n.put("signalCode", s.code().name());
      n.put("enabled", s.enabled());
      ObjectNode params = n.putObject("params");
      s.params().forEach(params::put);
    }
    return root;
  }

  AnomalyThresholds read(JsonNode v) {
    List<AnomalyThresholds.Signal> signals = new ArrayList<>();
    for (JsonNode n : v.path("signals")) {
      Map<String, BigDecimal> params = new LinkedHashMap<>();
      n.path("params")
          .properties()
          .forEach(e -> params.put(e.getKey(), e.getValue().decimalValue()));
      signals.add(
          new AnomalyThresholds.Signal(
              AnomalyThresholds.SignalCode.valueOf(n.path("signalCode").asText()),
              n.path("enabled").asBoolean(),
              params));
    }
    return new AnomalyThresholds(
        v.path("version").asInt(),
        v.path("reason").asText(null),
        v.hasNonNull("updatedAt") ? Instant.parse(v.get("updatedAt").asText()) : null,
        v.hasNonNull("updatedBy") ? UUID.fromString(v.get("updatedBy").asText()) : null,
        signals);
  }
}
