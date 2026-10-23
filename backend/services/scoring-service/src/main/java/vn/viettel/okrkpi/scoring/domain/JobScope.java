package vn.viettel.okrkpi.scoring.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.Jcs;

/**
 * Phạm vi của một tác vụ tính ({@code score_job.scope}): lý do, luồng, hồ sơ, đơn vị, phiên bản gói
 * gốc theo luồng ({@code bam_goi}, giá trị {@code <versionId>:<băm hex>}; tính lại hậu kiểm dùng
 * phiên bản gốc, BR-13-06) và, với {@code KIEM_TRA_TAI_LAP}, tác vụ gốc cùng danh sách kết quả cần
 * tính lại ({@code recheckOf}, {@code results} do DL-12 R4 ghi). Tập rỗng nghĩa là không giới hạn
 * theo chiều đó.
 */
public record JobScope(
    String reason,
    Set<String> flows,
    Set<UUID> profiles,
    Set<UUID> units,
    Map<String, String> policyHashes,
    UUID recheckOf,
    List<UUID> results) {

  public JobScope {
    flows = Set.copyOf(flows);
    profiles = Set.copyOf(profiles);
    units = Set.copyOf(units);
    policyHashes = Map.copyOf(policyHashes);
    results = List.copyOf(results);
  }

  /** Phạm vi theo lý do, không giới hạn. */
  public static JobScope of(String reason) {
    return new JobScope(reason, Set.of(), Set.of(), Set.of(), Map.of(), null, List.of());
  }

  public JobScope withFlows(Collection<String> value) {
    return new JobScope(
        reason, Set.copyOf(value), profiles, units, policyHashes, recheckOf, results);
  }

  public JobScope withProfiles(Collection<UUID> value) {
    return new JobScope(reason, flows, Set.copyOf(value), units, policyHashes, recheckOf, results);
  }

  public JobScope withUnits(Collection<UUID> value) {
    return new JobScope(
        reason, flows, profiles, Set.copyOf(value), policyHashes, recheckOf, results);
  }

  public JobScope withPolicyHashes(Map<String, String> value) {
    return new JobScope(reason, flows, profiles, units, value, recheckOf, results);
  }

  /** Phiên bản gói gốc của luồng, trống khi phạm vi không ghi. */
  public UUID originalVersion(String flow) {
    String v = policyHashes.get(flow);
    return v == null ? null : UUID.fromString(v.substring(0, v.indexOf(':')));
  }

  /** Băm gói gốc của luồng, trống khi phạm vi không ghi. */
  public String originalHash(String flow) {
    String v = policyHashes.get(flow);
    return v == null ? null : v.substring(v.indexOf(':') + 1);
  }

  /** Hồ sơ có thuộc phạm vi không. */
  public boolean includes(CoreScoringInput s) {
    return (flows.isEmpty() || flows.contains(s.flow()))
        && (profiles.isEmpty() || profiles.contains(s.profileId()))
        && (units.isEmpty() || (s.orgUnitId() != null && units.contains(s.orgUnitId())));
  }

  /** Gộp thêm hồ sơ vào phạm vi tính dần đang chờ; phạm vi không giới hạn thì giữ nguyên. */
  public JobScope mergeProfiles(Collection<UUID> more) {
    if (profiles.isEmpty()) {
      return this;
    }
    Set<UUID> merged = new LinkedHashSet<>(profiles);
    merged.addAll(more);
    return withProfiles(merged);
  }

  /** Tài liệu {@code scope} chuẩn hoá. */
  public CanonicalDoc toDoc() {
    Map<String, Object> doc = new LinkedHashMap<>();
    if (reason != null) {
      doc.put("ly_do", reason);
    }
    if (!flows.isEmpty()) {
      doc.put("luong", sorted(flows));
    }
    if (!profiles.isEmpty()) {
      doc.put("ho_so", sorted(profiles));
    }
    if (!units.isEmpty()) {
      doc.put("don_vi", sorted(units));
    }
    if (!policyHashes.isEmpty()) {
      doc.put("bam_goi", new TreeMap<>(policyHashes));
    }
    if (recheckOf != null) {
      doc.put("recheckOf", recheckOf.toString());
    }
    if (!results.isEmpty()) {
      doc.put("results", results.stream().map(UUID::toString).toList());
    }
    return CanonicalDoc.of(doc);
  }

  /** Đọc {@code scope} đã lưu; khoá lạ được bỏ qua. */
  public static JobScope parse(String json) {
    if (!(Jcs.parse(json) instanceof Map<?, ?> doc)) {
      throw new IllegalArgumentException("Phạm vi tác vụ phải là đối tượng JSON");
    }
    Map<String, String> hashes = new LinkedHashMap<>();
    if (doc.get("bam_goi") instanceof Map<?, ?> m) {
      m.forEach((k, v) -> hashes.put(String.valueOf(k), String.valueOf(v)));
    }
    Object recheck = doc.get("recheckOf");
    return new JobScope(
        doc.get("ly_do") == null ? null : String.valueOf(doc.get("ly_do")),
        new LinkedHashSet<>(strings(doc.get("luong"))),
        new LinkedHashSet<>(uuids(doc.get("ho_so"))),
        new LinkedHashSet<>(uuids(doc.get("don_vi"))),
        hashes,
        recheck == null ? null : UUID.fromString(String.valueOf(recheck)),
        uuids(doc.get("results")));
  }

  private static List<String> strings(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof Collection<?> c) {
      c.stream().filter(Objects::nonNull).forEach(v -> out.add(String.valueOf(v)));
    }
    return out;
  }

  private static List<UUID> uuids(Object value) {
    return strings(value).stream().map(UUID::fromString).toList();
  }

  private static List<String> sorted(Collection<?> values) {
    return values.stream().map(String::valueOf).sorted().toList();
  }
}
