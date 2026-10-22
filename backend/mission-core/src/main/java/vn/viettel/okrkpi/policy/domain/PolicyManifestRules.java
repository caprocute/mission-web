package vn.viettel.okrkpi.policy.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm lược đồ manifest gói khi nhập gói gốc (TL-01 "Cấu trúc gói"): {@code apiVersion}, {@code
 * kind}, {@code metadata} (mã, phiên bản, luồng, tên) và các khối {@code inputs}, {@code
 * definitions}, {@code rounding}, {@code classification}, {@code quotas}. Khoá khác được giữ
 * nguyên.
 */
public final class PolicyManifestRules {

  public static final String API_VERSION = "policy.okrkpi.vn/v1";
  public static final String KIND = "PolicyPack";

  /** Mã gói dạng {@code khoi-dang/quy-dinh-moi} ({@code policy_pack.code varchar(100)}). */
  public static final Pattern PACK_CODE = Pattern.compile("^[a-z0-9-]+(/[a-z0-9-]+)*$");

  private static final List<String> REQUIRED_BLOCKS =
      List.of("inputs", "definitions", "rounding", "classification", "quotas");
  private static final Set<String> NON_EMPTY = Set.of("inputs", "definitions");
  private static final Set<String> FLOWS = Set.of("DANG", "NHA_NUOC");

  /** Một chỗ sai: {@code field} là đường dẫn chấm trong manifest. */
  public record Problem(String field, String message) {}

  private PolicyManifestRules() {}

  public static List<Problem> check(Map<String, ?> manifest) {
    List<Problem> problems = new ArrayList<>();
    if (manifest == null) {
      problems.add(new Problem("manifest", "Thiếu nội dung gói"));
      return problems;
    }
    if (!API_VERSION.equals(manifest.get("apiVersion"))) {
      problems.add(new Problem("apiVersion", "Phiên bản lược đồ gói phải là " + API_VERSION));
    }
    if (!KIND.equals(manifest.get("kind"))) {
      problems.add(new Problem("kind", "Loại tài liệu phải là " + KIND));
    }
    if (manifest.get("metadata") instanceof Map<?, ?> meta) {
      Object id = meta.get("id");
      if (!(id instanceof String s) || s.length() > 100 || !PACK_CODE.matcher(s).matches()) {
        problems.add(new Problem("metadata.id", "Mã gói chỉ gồm chữ thường, số, gạch ngang và /"));
      }
      Object version = meta.get("version");
      if (!(version instanceof String v) || SemVer.parse(v).isEmpty()) {
        problems.add(new Problem("metadata.version", "Phiên bản gói phải có dạng 1.0.0"));
      }
      if (!(meta.get("luong") instanceof String flow) || !FLOWS.contains(flow)) {
        problems.add(new Problem("metadata.luong", "Luồng đánh giá phải là DANG hoặc NHA_NUOC"));
      }
      if (!(meta.get("ten") instanceof String name) || name.isBlank()) {
        problems.add(new Problem("metadata.ten", "Thiếu tên gói"));
      }
    } else {
      problems.add(new Problem("metadata", "Thiếu thông tin định danh gói"));
    }
    for (String block : REQUIRED_BLOCKS) {
      Object value = manifest.get(block);
      if (!(value instanceof Map<?, ?> map)) {
        problems.add(new Problem(block, "Thiếu khối " + block));
      } else if (NON_EMPTY.contains(block) && map.isEmpty()) {
        problems.add(new Problem(block, "Khối " + block + " không được rỗng"));
      }
    }
    return problems;
  }
}
