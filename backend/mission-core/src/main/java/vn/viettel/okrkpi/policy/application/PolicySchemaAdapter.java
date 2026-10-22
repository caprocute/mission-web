package vn.viettel.okrkpi.policy.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.policy.domain.SemVerRange;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực cổng {@link PolicySchemaPort} của MOD-08 (đảo phụ thuộc, CORE-05-03): giải {@code
 * schemaRef} dạng {@code policy://<mã gói>/forms/<biểu mẫu>@<phiên bản>} trong khối {@code forms}
 * của manifest, báo gói đã ngừng, liệt kê trường tính điểm theo khối {@code inputs}.
 */
public class PolicySchemaAdapter implements PolicySchemaPort {

  static final Pattern SCHEMA_REF =
      Pattern.compile("^policy://(.+)/forms/([A-Za-z0-9_.-]+)@([0-9]+\\.[0-9]+\\.[0-9]+)$");

  private static final Map<String, String> JSON_TYPES =
      Map.ofEntries(
          Map.entry("double", "number"),
          Map.entry("float", "number"),
          Map.entry("decimal", "number"),
          Map.entry("number", "number"),
          Map.entry("int", "integer"),
          Map.entry("integer", "integer"),
          Map.entry("long", "integer"),
          Map.entry("string", "string"),
          Map.entry("bool", "boolean"),
          Map.entry("boolean", "boolean"));

  private final PolicyResolutionService resolution;
  private final PolicyPackRepository packs;

  public PolicySchemaAdapter(PolicyResolutionService resolution, PolicyPackRepository packs) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.packs = Objects.requireNonNull(packs, "packs");
  }

  @Override
  public Optional<Set<String>> fields(TenantId tenantId, String schemaRef, String policyPackRange) {
    Matcher m = schemaRef == null ? null : SCHEMA_REF.matcher(schemaRef);
    if (m == null || !m.matches()) {
      return Optional.empty();
    }
    String code = m.group(1);
    String version = m.group(3);
    if (!inRange(code, version, policyPackRange)) {
      return Optional.empty();
    }
    return resolution
        .manifestByCode(tenantId, code, version)
        .map(manifest -> manifest.get("forms"))
        .filter(Map.class::isInstance)
        .map(forms -> ((Map<?, ?>) forms).get(m.group(2)))
        .flatMap(PolicySchemaAdapter::fieldNames);
  }

  @Override
  public boolean retired(TenantId tenantId, String policyPackRef) {
    int at = policyPackRef == null ? -1 : policyPackRef.indexOf('@');
    if (at <= 0) {
      return false;
    }
    String code = policyPackRef.substring(0, at);
    Optional<SemVerRange> range = SemVerRange.parse(policyPackRef.substring(at + 1));
    if (range.isEmpty()) {
      return false;
    }
    List<PolicyPackVersion> matching =
        packs.findPackByCode(tenantId, code).stream()
            .flatMap(p -> packs.listVersions(tenantId, p.id(), null, null, 200).stream())
            .filter(v -> range.get().contains(v.semVer()))
            .filter(
                v ->
                    v.status() == PolicyVersionStatus.DA_PHAT_HANH
                        || v.status() == PolicyVersionStatus.NGUNG)
            .toList();
    return !matching.isEmpty()
        && matching.stream().allMatch(v -> v.status() == PolicyVersionStatus.NGUNG);
  }

  @Override
  public Map<String, String> scoredFields(TenantId tenantId, UUID policyPackVersionId) {
    if (policyPackVersionId == null) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    resolution
        .manifest(tenantId, policyPackVersionId)
        .map(m -> m.get("inputs"))
        .filter(Map.class::isInstance)
        .ifPresent(
            inputs ->
                ((Map<?, ?>) inputs)
                    .forEach(
                        (k, v) -> {
                          Object type = v instanceof Map<?, ?> spec ? spec.get("type") : null;
                          String mapped =
                              type == null ? null : JSON_TYPES.get(String.valueOf(type));
                          if (mapped != null) {
                            result.put(String.valueOf(k), mapped);
                          }
                        }));
    return result;
  }

  private static boolean inRange(String code, String version, String policyPackRange) {
    if (policyPackRange == null || policyPackRange.isBlank()) {
      return true;
    }
    int at = policyPackRange.indexOf('@');
    if (at <= 0 || !policyPackRange.substring(0, at).equals(code)) {
      return false;
    }
    return SemVerRange.parse(policyPackRange.substring(at + 1))
        .map(r -> r.contains(SemVer.parse(version).orElseThrow()))
        .orElse(false);
  }

  private static Optional<Set<String>> fieldNames(Object form) {
    if (!(form instanceof Map<?, ?> spec)) {
      return Optional.empty();
    }
    Set<String> names = new LinkedHashSet<>();
    if (spec.get("properties") instanceof Map<?, ?> props) {
      props.keySet().forEach(k -> names.add(String.valueOf(k)));
    }
    if (spec.get("fields") instanceof Collection<?> list) {
      for (Object f : list) {
        Object name = f instanceof Map<?, ?> fm ? fm.get("name") : f;
        if (name != null) {
          names.add(String.valueOf(name));
        }
      }
    }
    return names.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(names));
  }
}
