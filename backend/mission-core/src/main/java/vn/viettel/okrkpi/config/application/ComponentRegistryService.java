package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.application.port.ComponentRegistryRepository;
import vn.viettel.okrkpi.config.application.port.PluginWidgets;
import vn.viettel.okrkpi.config.domain.RegistryVersion;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Component registry có phiên bản của SDUI (CORE-08-09, QT-18, SCR-1801, ADR-05 lớp 2): nạp
 * manifest phát hành cùng shell, trả danh mục cho trình biên tập bố cục ({@code listUiComponents})
 * và cho bộ kiểm bố cục {@link LayoutValidator}. Phiên bản đã nạp không sửa, chỉ nạp phiên bản mới.
 */
public class ComponentRegistryService {

  /** Bộ đệm danh mục theo phiên bản; danh mục chỉ đổi khi vận hành ngừng một component. */
  private static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final ComponentRegistryRepository repo;
  private final PluginWidgets plugins;
  private final ObjectMapper json;
  private final Cache<String, List<UiComponent>> cache =
      Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).maximumSize(64).build();

  public ComponentRegistryService(
      ComponentRegistryRepository repo, PluginWidgets plugins, ObjectMapper json) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.plugins = plugins == null ? PluginWidgets.NONE : plugins;
    this.json = Objects.requireNonNull(json, "json");
  }

  /** Phiên bản registry mới nhất đã nạp. */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Optional<String> latestVersion() {
    return repo.versions().stream().max(RegistryVersion.ORDER);
  }

  /**
   * Phiên bản dùng để kiểm một UI Pack: đúng phiên bản khai báo nếu đã nạp, nếu không thì phiên bản
   * mới nhất thuộc dải {@code requires.registry}.
   */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Optional<String> resolve(String range) {
    if (range == null || range.isBlank()) {
      return Optional.empty();
    }
    List<String> versions = repo.versions();
    if (versions.contains(range.trim())) {
      return Optional.of(range.trim());
    }
    return versions.stream()
        .filter(v -> RegistryVersion.satisfies(range, v))
        .max(RegistryVersion.ORDER);
  }

  /**
   * Danh mục cho trình biên tập: component của nền tảng và widget plugin của tenant, theo khoá tăng
   * dần.
   *
   * @param registry phiên bản, {@code null} là mới nhất
   * @param status {@code null} là mọi trạng thái
   */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public List<UiComponent> list(
      TenantId tenantId, String registry, UiComponent.Status status, String afterKey, int limit) {
    String version = registry == null ? latestVersion().orElse(null) : registry;
    if (version == null) {
      return List.of();
    }
    if (!repo.versions().contains(version)) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Phiên bản danh mục thành phần không tồn tại.",
          List.of(new ProblemFieldError("registry", "Phiên bản chưa được phát hành", null)));
    }
    return components(tenantId, version).stream()
        .filter(c -> status == null || c.status() == status)
        .filter(c -> afterKey == null || c.componentKey().compareTo(afterKey) > 0)
        .limit(limit)
        .toList();
  }

  /** Mọi component của phiên bản kể cả widget plugin của tenant, theo khoá tăng dần. */
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public List<UiComponent> components(TenantId tenantId, String version) {
    List<UiComponent> all = new ArrayList<>(cache.get(version, repo::all));
    Set<String> keys = new HashSet<>();
    all.forEach(c -> keys.add(c.componentKey()));
    for (UiComponent p : plugins.forTenant(tenantId, version)) {
      if (keys.add(p.componentKey())) {
        all.add(p);
      }
    }
    all.sort(Comparator.comparing(UiComponent::componentKey));
    return all;
  }

  /** Kết quả nạp manifest. */
  public record LoadResult(String registryVersion, boolean loaded, int components) {}

  /**
   * Nạp manifest registry phát hành cùng shell. Phiên bản đã có thì bỏ qua, không ghi đè.
   *
   * @throws IllegalArgumentException manifest sai định dạng
   */
  @Transactional(UiPackService.TX)
  public LoadResult load(String manifestJson) {
    Manifest manifest = parse(manifestJson);
    if (repo.versions().contains(manifest.version())) {
      return new LoadResult(manifest.version(), false, manifest.components().size());
    }
    repo.insertVersion(manifest.components());
    cache.invalidate(manifest.version());
    return new LoadResult(manifest.version(), true, manifest.components().size());
  }

  private record Manifest(String version, List<UiComponent> components) {}

  private Manifest parse(String manifestJson) {
    JsonNode root;
    try {
      root = json.readTree(manifestJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Manifest registry không phải JSON hợp lệ", e);
    }
    String version = root.path("registryVersion").asText("");
    JsonNode items = root.path("components");
    if (!items.isArray() || items.isEmpty()) {
      throw new IllegalArgumentException("Manifest registry " + version + " không có component");
    }
    List<UiComponent> components = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (JsonNode item : items) {
      String name = item.path("name").asText("");
      if (!names.add(name)) {
        throw new IllegalArgumentException("Component trùng tên trong manifest: " + name);
      }
      JsonNode props = item.path("propsSchema");
      if (!"object".equals(props.path("type").asText())
          || !props.path("additionalProperties").isBoolean()
          || props.path("additionalProperties").asBoolean()) {
        throw new IllegalArgumentException(
            "propsSchema của " + name + " phải là object có additionalProperties: false");
      }
      // Từ khoá lạ bị từ chối ngay khi nạp, để bộ kiểm bố cục luôn hiểu lược đồ.
      UiPackSchema.of(props);
      components.add(
          new UiComponent(
              name,
              version,
              props.toString(),
              item.path("allowedClassification").asText("C1"),
              UiComponent.Status.HIEU_LUC,
              item.path("container").asBoolean(false),
              item.hasNonNull("since") ? item.get("since").asText() : null,
              item.path("mobile").asBoolean(false),
              null));
    }
    return new Manifest(version, List.copyOf(components));
  }
}
