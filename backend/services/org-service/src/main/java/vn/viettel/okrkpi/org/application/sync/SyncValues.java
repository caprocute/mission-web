package vn.viettel.okrkpi.org.application.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Post;

/**
 * Mã hoá giá trị hai phía của một dòng lệch thành JSON (trước khi mã hoá trường C2) và dựng nhãn hiển thị tiếng Việt
 * đã che cho SCR-0104.
 */
final class SyncValues {

  static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  static final Map<String, String> STATUS_LABELS = Map.of("DANG_CONG_TAC", "Đang công tác", "NGHI_HUU", "Nghỉ hưu",
      "THOI_VIEC", "Thôi việc", "TAM_NGHI", "Tạm nghỉ");

  private final ObjectMapper json;

  SyncValues(ObjectMapper json) {
    this.json = json;
  }

  byte[] encode(Object value) {
    if (value == null) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    switch (value) {
      case Post p -> {
        m.put("type", "post");
        m.put("assignmentId", str(p.assignmentId()));
        m.put("orgUnitId", str(p.orgUnitId()));
        m.put("jobTitleId", str(p.jobTitleId()));
        m.put("kind", p.kind());
        m.put("head", p.head());
        m.put("validFrom", str(p.validFrom()));
      }
      case Identity i -> {
        m.put("type", "identity");
        m.put("fullName", i.fullName());
        m.put("birthDate", str(i.birthDate()));
      }
      case Boolean b -> {
        m.put("type", "party");
        m.put("value", b);
      }
      case String s -> {
        m.put("type", "status");
        m.put("value", s);
      }
      default -> throw new IllegalArgumentException(value.getClass().getName());
    }
    try {
      return json.writeValueAsBytes(m);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  Object decode(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = json.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
      return switch (String.valueOf(m.get("type"))) {
        case "post" -> new Post(uuid(m.get("assignmentId")), uuid(m.get("orgUnitId")), uuid(m.get("jobTitleId")),
            (String) m.get("kind"), Boolean.TRUE.equals(m.get("head")), date(m.get("validFrom")));
        case "identity" -> new Identity((String) m.get("fullName"), date(m.get("birthDate")));
        case "party" -> Boolean.TRUE.equals(m.get("value"));
        default -> (String) m.get("value");
      };
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Nhãn đã che: không bao giờ trả ngày sinh đầy đủ hay số định danh. */
  static String label(Object value, Map<UUID, String> units, Map<UUID, String> titles) {
    return switch (value) {
      case null -> null;
      case Post p -> {
        StringBuilder b = new StringBuilder();
        b.append(titles.getOrDefault(p.jobTitleId(), "Chức danh không xác định"));
        b.append(", ").append(units.getOrDefault(p.orgUnitId(), "Đơn vị không xác định"));
        if (!"CHINH".equals(p.kind())) {
          b.append(" (kiêm nhiệm)");
        }
        if (p.validFrom() != null) {
          b.append(", từ ").append(VN_DATE.format(p.validFrom()));
        }
        yield b.toString();
      }
      case Identity i -> i.fullName() + (i.birthDate() == null ? "" : ", sinh năm " + i.birthDate().getYear());
      case Boolean b -> b ? "Đảng viên" : "Không phải đảng viên";
      case String s -> STATUS_LABELS.getOrDefault(s, s);
      default -> null;
    };
  }

  /** Số định danh che 4 số cuối (TL-02a SCR-0104 trường 3). */
  static String maskNationalId(String nationalId) {
    if (nationalId == null || nationalId.length() <= 4) {
      return nationalId == null ? null : "****";
    }
    return nationalId.substring(0, nationalId.length() - 4) + "****";
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static UUID uuid(Object o) {
    return o == null ? null : UUID.fromString(o.toString());
  }

  private static LocalDate date(Object o) {
    return o == null ? null : LocalDate.parse(o.toString());
  }

  /** Danh tính phía nguồn của dòng không khớp người. */
  record Identity(String fullName, LocalDate birthDate) {
  }
}
