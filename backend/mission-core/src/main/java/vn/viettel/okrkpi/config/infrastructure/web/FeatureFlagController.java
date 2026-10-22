package vn.viettel.okrkpi.config.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.FeatureFlagService;
import vn.viettel.okrkpi.config.application.TenantSettingService;
import vn.viettel.okrkpi.config.domain.AnomalyThresholds;
import vn.viettel.okrkpi.config.domain.FeatureFlag;
import vn.viettel.okrkpi.config.domain.FeatureFlagRollout;
import vn.viettel.okrkpi.contract.config.model.AnomalySignalThreshold;
import vn.viettel.okrkpi.contract.config.model.AnomalyThresholdSet;
import vn.viettel.okrkpi.contract.config.model.AnomalyThresholdUpdate;
import vn.viettel.okrkpi.contract.config.model.ConfigAnomalySignalCode;
import vn.viettel.okrkpi.contract.config.model.FeatureFlagInput;
import vn.viettel.okrkpi.contract.config.model.FeatureFlagPage;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cờ tính năng và ngưỡng phát hiện bất thường của {@code config.yaml} (CORE-08-07): {@code
 * listFeatureFlags}, {@code setFeatureFlag} cho quản trị tenant (QT-18, SCR-1801), {@code
 * getAnomalyThresholds}, {@code updateAnomalyThresholds} cho người soạn chính sách (QT-14,
 * SCR-1401.nguong).
 */
@RestController
@RequestMapping("/api/v1/config")
public class FeatureFlagController {

  private static final String FLAGS = "/api/v1/config/feature-flags";
  private static final String THRESHOLDS = "/api/v1/config/anomaly-thresholds";

  private final FeatureFlagService flags;
  private final TenantSettingService settings;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;

  public FeatureFlagController(
      FeatureFlagService flags,
      TenantSettingService settings,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency) {
    this.flags = Objects.requireNonNull(flags, "flags");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
  }

  @GetMapping("/feature-flags")
  @RequiresPermission(operationId = "listFeatureFlags", module = "config")
  public FeatureFlagPage listFeatureFlags(
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, "feature-flags");
    List<FeatureFlag> fetched =
        flags.list(TenantContext.require()).stream()
            .filter(
                f -> page.afterKey().map(k -> f.key().compareTo(k.getString(0)) > 0).orElse(true))
            .limit(page.fetchSize())
            .toList();
    CursorPage<FeatureFlag> result = cursors.page(fetched, page, f -> CursorKey.of(f.key()));
    return new FeatureFlagPage(
        result.items().stream().map(FeatureFlagController::toDto).toList(), result.nextCursor());
  }

  @PutMapping("/feature-flags/{key}")
  @RequiresPermission(operationId = "setFeatureFlag", module = "config")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.FeatureFlag> setFeatureFlag(
      @PathVariable("key") String key,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody FeatureFlagInput body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        FLAGS + "/" + key,
        body,
        vn.viettel.okrkpi.contract.config.model.FeatureFlag.class,
        vn.viettel.okrkpi.contract.config.model.FeatureFlag::getRowVersion,
        () -> {
          FeatureFlag saved;
          try {
            saved =
                flags.set(
                    tenant,
                    actor,
                    key,
                    expected,
                    Boolean.TRUE.equals(body.getEnabled()),
                    rollout(body),
                    instant(body.getValidFrom()),
                    instant(body.getValidTo()));
          } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
          }
          return ETags.ok(toDto(saved), saved.rowVersion());
        });
  }

  @GetMapping("/anomaly-thresholds")
  @RequiresPermission(operationId = "getAnomalyThresholds", module = "config")
  public ResponseEntity<AnomalyThresholdSet> getAnomalyThresholds() {
    TenantSettingService.Versioned v = settings.anomalyThresholds(TenantContext.require());
    return ETags.ok(toDto(v), v.rowVersion());
  }

  @PutMapping("/anomaly-thresholds")
  @RequiresPermission(
      action = "SCR-1401.nguong",
      operationId = "updateAnomalyThresholds",
      module = "config")
  public ResponseEntity<AnomalyThresholdSet> updateAnomalyThresholds(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AnomalyThresholdUpdate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        THRESHOLDS,
        body,
        AnomalyThresholdSet.class,
        AnomalyThresholdSet::getRowVersion,
        () -> {
          TenantSettingService.Versioned v =
              settings.updateAnomalyThresholds(
                  tenant,
                  actor,
                  expected,
                  body.getSignals() == null
                      ? List.of()
                      : body.getSignals().stream().map(FeatureFlagController::signal).toList(),
                  body.getReason());
          return ETags.ok(toDto(v), v.rowVersion());
        });
  }

  private static FeatureFlagRollout rollout(FeatureFlagInput body) {
    vn.viettel.okrkpi.contract.config.model.FeatureFlagRollout r = body.getRollout();
    if (r == null) {
      return FeatureFlagRollout.ALL;
    }
    int percent = r.getPercent() == null ? 100 : r.getPercent();
    if (percent < 0 || percent > 100) {
      throw new BusinessException("COMMON-422-VALIDATION", "Tỷ lệ bật phải từ 0 đến 100.");
    }
    return new FeatureFlagRollout(
        r.getUnitIds() == null ? null : new HashSet<>(r.getUnitIds()), percent);
  }

  private static AnomalyThresholds.Signal signal(AnomalySignalThreshold s) {
    if (s.getSignalCode() == null) {
      throw new BusinessException("COMMON-422-VALIDATION", "Thiếu mã dấu hiệu.");
    }
    Map<String, BigDecimal> params = s.getParams() == null ? Map.of() : s.getParams();
    return new AnomalyThresholds.Signal(
        AnomalyThresholds.SignalCode.valueOf(s.getSignalCode().getValue()),
        Boolean.TRUE.equals(s.getEnabled()),
        params);
  }

  static vn.viettel.okrkpi.contract.config.model.FeatureFlag toDto(FeatureFlag f) {
    vn.viettel.okrkpi.contract.config.model.FeatureFlag dto =
        new vn.viettel.okrkpi.contract.config.model.FeatureFlag(
            f.key(), f.enabled(), f.rowVersion());
    dto.setDescription(f.description());
    vn.viettel.okrkpi.contract.config.model.FeatureFlagRollout r =
        new vn.viettel.okrkpi.contract.config.model.FeatureFlagRollout();
    r.setUnitIds(f.rollout().unitIds().stream().sorted().toList());
    r.setPercent(f.rollout().percent());
    dto.setRollout(r);
    dto.setValidFrom(offset(f.validFrom()));
    dto.setValidTo(offset(f.validTo()));
    return dto;
  }

  static AnomalyThresholdSet toDto(TenantSettingService.Versioned v) {
    AnomalyThresholds t = v.thresholds();
    AnomalyThresholdSet dto =
        new AnomalyThresholdSet(
            t.version(),
            t.signals().stream()
                .map(
                    s ->
                        new AnomalySignalThreshold(
                            ConfigAnomalySignalCode.fromValue(s.code().name()),
                            s.enabled(),
                            new java.util.LinkedHashMap<>(s.params())))
                .toList(),
            v.rowVersion());
    dto.setReason(t.reason());
    dto.setUpdatedAt(offset(t.updatedAt()));
    return dto;
  }

  private static Instant instant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
