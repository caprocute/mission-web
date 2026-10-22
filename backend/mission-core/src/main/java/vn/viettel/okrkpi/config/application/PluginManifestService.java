package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.config.application.port.PluginWidgets;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.domain.PluginRelease;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Manifest plugin chuyên ngành của tenant (CORE-08-15, BR-18-12, ADR-05 lớp 4, ADR-16). Tenant bật
 * plugin bằng cấu hình {@value #SETTING_KEY} ({@code json.string-array}, phần tử {@code tên} hoặc
 * {@code tên@phiên bản}); plugin chỉ vào manifest khi có trong {@link PluginCatalog} và đạt mọi
 * kiểm tra. Là bộ kiểm của job {@code TEST_RUN} (mã {@code REGISTRY}), nguồn widget plugin của
 * {@code listUiComponents}, và dựng phần {@code plugins} của gói lúc phát hành.
 */
public class PluginManifestService implements UiPackChecker, PluginWidgets {

  public static final String SETTING_KEY = "ui.plugins";

  private final PluginCatalog catalog;
  private final TenantSettingApi settings;
  private final List<String> origins;
  private final ObjectMapper json;

  public PluginManifestService(
      PluginCatalog catalog, TenantSettingApi settings, List<String> origins, ObjectMapper json) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.origins = origins == null ? List.of() : List.copyOf(origins);
    this.json = Objects.requireNonNull(json, "json");
  }

  /** Plugin tenant yêu cầu bật, theo thứ tự cấu hình. */
  public List<String> requested(TenantId tenantId) {
    List<String> out = new ArrayList<>();
    settings
        .get(tenantId, SETTING_KEY, null)
        .ifPresent(v -> v.forEach(x -> out.add(x.asText().strip())));
    return out.stream().filter(s -> !s.isEmpty()).distinct().toList();
  }

  private record Resolved(
      String requested, Optional<PluginRelease> release, List<String> problems) {
    boolean ok() {
      return release.isPresent() && problems.isEmpty();
    }
  }

  private List<Resolved> resolve(TenantId tenantId) {
    List<Resolved> out = new ArrayList<>();
    for (String r : requested(tenantId)) {
      int at = r.indexOf('@');
      String name = at < 0 ? r : r.substring(0, at);
      String version = at < 0 ? null : r.substring(at + 1);
      Optional<PluginRelease> release = catalog.find(name, version);
      List<String> problems =
          release
              .map(p -> PluginCatalog.problems(p, origins))
              .orElse(List.of("Plugin " + r + " không có trong danh mục plugin đã được duyệt."));
      out.add(new Resolved(r, release, problems));
    }
    return out;
  }

  @Override
  public List<Result> check(TenantId tenantId, JsonNode document) {
    List<Resolved> all = resolve(tenantId);
    if (all.isEmpty()) {
      return List.of();
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      for (String p : all.get(i).problems()) {
        errors.add(new ProblemFieldError("plugins[" + i + "]", p, "BR-18-12"));
      }
    }
    return List.of(
        errors.isEmpty()
            ? Result.ok(Code.REGISTRY, "Plugin chuyên ngành đã được duyệt, ký số và kiểm toàn vẹn.")
            : new Result(
                Code.REGISTRY,
                false,
                "Có plugin chuyên ngành chưa đủ điều kiện nạp.",
                null,
                errors));
  }

  @Override
  public List<UiComponent> forTenant(TenantId tenantId, String registryVersion) {
    List<UiComponent> out = new ArrayList<>();
    for (Resolved r : resolve(tenantId)) {
      if (!r.ok()) {
        continue;
      }
      PluginRelease p = r.release().orElseThrow();
      for (PluginRelease.Widget w : p.widgets()) {
        if (w.registry() == null || w.registry().equals(registryVersion)) {
          out.add(p.component(registryVersion, w));
        }
      }
    }
    return out;
  }

  /**
   * Phần {@code plugins} của gói lúc phát hành: {@code {name, remoteEntry, integrity}} của các
   * plugin đạt.
   *
   * @throws BusinessException khi có plugin không đạt (job phát hành thất bại kèm lý do)
   */
  public ArrayNode manifest(TenantId tenantId) {
    ArrayNode out = json.createArrayNode();
    for (Resolved r : resolve(tenantId)) {
      if (!r.ok()) {
        throw new BusinessException("COMMON-422-VALIDATION", r.problems().get(0));
      }
      PluginRelease p = r.release().orElseThrow();
      out.addObject()
          .put("name", p.name())
          .put("remoteEntry", p.remoteEntry())
          .put("integrity", p.integrity());
    }
    return out;
  }
}
