package vn.viettel.okrkpi.analytics.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Một mắt xích của chuỗi truy vết ({@code TraceLink}). Mắt xích ngoài phạm vi chỉ còn loại, mã,
 * nhãn chung và {@code MSG-16-06} (BR-16-04).
 */
public record TraceLink(
    TraceObjectType objectType,
    UUID id,
    String label,
    String value,
    String status,
    Integer versionNo,
    String unitName,
    boolean masked,
    String msg,
    Map<String, Object> details) {

  public static final String MSG_MASKED = "MSG-16-06";
  public static final String MSG_VERSIONS = "MSG-16-07";

  public TraceLink {
    Objects.requireNonNull(objectType, "objectType");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(label, "label");
    details = details == null || details.isEmpty() ? null : Map.copyOf(details);
  }

  public static TraceLink masked(TraceObjectType type, UUID id) {
    return new TraceLink(
        type, id, maskedLabel(type), null, null, null, null, true, MSG_MASKED, null);
  }

  /** Nhãn chung của mắt xích bị che: chỉ nói loại đối tượng, không lộ nội dung. */
  public static String maskedLabel(TraceObjectType type) {
    return switch (type) {
      case DIEM -> "Điểm đánh giá";
      case CAU_PHAN -> "Cấu phần điểm";
      case KPI_NHIEM_VU -> "Nhiệm vụ";
      case KET_QUA_THEN_CHOT -> "Kết quả then chốt";
      case SAN_PHAM -> "Sản phẩm";
      case MINH_CHUNG -> "Minh chứng";
      case KE_HOACH -> "Kế hoạch";
      case MUC_TIEU -> "Mục tiêu";
      case CAN_CU -> "Căn cứ";
    };
  }
}
