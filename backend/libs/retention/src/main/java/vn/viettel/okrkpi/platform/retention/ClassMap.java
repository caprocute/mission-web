package vn.viettel.okrkpi.platform.retention;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;

/**
 * Ánh xạ bảng sang lớp lưu giữ và lớp sang mục đích DEK (suy luận từ cột "Dữ liệu" của bảng TL-03).
 * Mặc định theo schema; ghi đè bằng thuộc tính {@code lop.<schema>=LỚP}, {@code
 * lop.<schema>.<bảng>=LỚP}, {@code muc-dich.<LỚP>=MỤC_ĐÍCH,...}.
 */
public final class ClassMap {

  private static final Map<String, RetentionClass> SCHEMA_DEFAULTS =
      Map.ofEntries(
          Map.entry("appraisal", RetentionClass.HO_SO_CAN_BO),
          Map.entry("dossier", RetentionClass.HO_SO_CAN_BO),
          Map.entry("esign", RetentionClass.HO_SO_CAN_BO),
          Map.entry("docgen", RetentionClass.HO_SO_CAN_BO),
          Map.entry("oversight", RetentionClass.KHIEU_NAI),
          Map.entry("okr", RetentionClass.THUC_HIEN),
          Map.entry("task", RetentionClass.THUC_HIEN),
          Map.entry("product", RetentionClass.THUC_HIEN),
          Map.entry("evidence", RetentionClass.THUC_HIEN),
          Map.entry("ledger", RetentionClass.SO_CAI),
          Map.entry("scoring", RetentionClass.VET_TINH),
          Map.entry("notify", RetentionClass.VAN_HANH),
          Map.entry("integ", RetentionClass.VAN_HANH),
          Map.entry("ai", RetentionClass.VAN_HANH));

  /** Bảng kỹ thuật của mọi schema (NEN-032). */
  private static final List<String> TECHNICAL =
      List.of("outbox_event", "inbox_processed", "inbox_aggregate_version", "idempotency_key");

  private final Map<String, RetentionClass> overrides = new HashMap<>();
  private final Map<RetentionClass, List<DekPurpose>> purposes =
      new EnumMap<>(RetentionClass.class);

  public ClassMap(Properties props) {
    purposes.put(RetentionClass.THUC_HIEN, List.of(DekPurpose.TEP_MINH_CHUNG));
    purposes.put(RetentionClass.VET_TINH, List.of());
    for (String k : props.stringPropertyNames()) {
      String v = props.getProperty(k).trim();
      if (k.startsWith("lop.")) {
        overrides.put(k.substring(4), RetentionClass.valueOf(v));
      } else if (k.startsWith("muc-dich.")) {
        purposes.put(
            RetentionClass.valueOf(k.substring(9)),
            v.isEmpty()
                ? List.of()
                : java.util.Arrays.stream(v.split(","))
                    .map(String::trim)
                    .map(DekPurpose::valueOf)
                    .toList());
      }
    }
  }

  public static ClassMap defaults() {
    return new ClassMap(new Properties());
  }

  /** Lớp của bảng; {@code null} khi không xác định được (job bỏ qua và báo). */
  public RetentionClass of(String schema, String table) {
    RetentionClass c = overrides.get(schema + "." + table);
    if (c != null) {
      return c;
    }
    if (TECHNICAL.contains(table)) {
      return RetentionClass.KY_THUAT;
    }
    c = overrides.get(schema);
    return c != null ? c : SCHEMA_DEFAULTS.get(schema);
  }

  /** Mục đích DEK huỷ khi lớp hết tổng thời hạn (chỉ lớp xoá bằng mật mã). */
  public List<DekPurpose> purposes(RetentionClass c) {
    return purposes.getOrDefault(c, List.of());
  }
}
