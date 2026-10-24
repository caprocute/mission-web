package vn.viettel.okrkpi.config.infrastructure.web;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.FeatureFlagApi;
import vn.viettel.okrkpi.config.application.FeatureFlagService;
import vn.viettel.okrkpi.config.application.UiPackPublishService;
import vn.viettel.okrkpi.contract.config.model.EffectiveFeatureFlagMap;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cờ tính năng hiệu lực của người dùng hiện hành ({@code getEffectiveFeatureFlags} của {@code
 * config.yaml}, QT-00, SCR-0002, TL-04 OPS-04, CORE-08-07): shell web và ứng dụng di động đọc bản
 * đồ {@code mã cờ -> bật/tắt} mà không cần quyền quản trị tenant. Đơn vị xét theo đơn vị của ngữ
 * cảnh phân công đang chọn cùng các đơn vị cấp trên; tỷ lệ bật dần băm theo mã người dùng.
 */
@RestController
@RequestMapping("/api/v1/ui")
public class EffectiveFeatureFlagController {

  private final FeatureFlagService flags;
  private final Callers callers;
  private final ObjectProvider<AccessDirectory> directory;

  public EffectiveFeatureFlagController(
      FeatureFlagService flags,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ObjectProvider<AccessDirectory> directory) {
    this.flags = Objects.requireNonNull(flags, "flags");
    this.callers = new Callers(subjects, periodCallers);
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  @GetMapping("/feature-flags")
  @RequiresPermission(operationId = "getEffectiveFeatureFlags", module = "ui")
  public EffectiveFeatureFlagMap getEffectiveFeatureFlags() {
    TenantId tenant = TenantContext.require();
    UiPackPublishService.Viewer viewer = callers.viewer();
    FeatureFlagService.Effective result =
        flags.effective(
            tenant,
            new FeatureFlagApi.FlagContext(viewer.userId(), units(tenant, viewer.unitId())));
    return new EffectiveFeatureFlagMap(
        result.flags(), result.evaluatedAt().atOffset(ZoneOffset.UTC));
  }

  /** Đơn vị đang chọn và các đơn vị cấp trên; mã đơn vị sai dạng thì coi như không có đơn vị. */
  private List<UUID> units(TenantId tenant, String unitId) {
    if (unitId == null || unitId.isBlank()) {
      return List.of();
    }
    UUID unit;
    try {
      unit = UUID.fromString(unitId);
    } catch (IllegalArgumentException e) {
      return List.of();
    }
    List<UUID> units = new ArrayList<>();
    units.add(unit);
    AccessDirectory dir = directory.getIfAvailable();
    if (dir != null) {
      units.addAll(dir.ancestorsOf(tenant, unit));
    }
    return units;
  }
}
