package vn.viettel.okrkpi.oversight.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Một tài liệu của chỉ mục kiểm toán {@code audit-*} (TL-01 "Tách nhật ký kiểm toán", CORE-07-12):
 * một đối tượng trong một sự kiện {@code sys.audit.v1}. Chỉ mang siêu dữ liệu (tác nhân, phân công
 * và vai trò đang chọn, đối tượng, toạ độ {@code schema.bảng.cột}, mục đích, {@code traceparent},
 * thời điểm, cờ "của chính mình", cờ hệ thống); không bao giờ có bản rõ hay bản mã C2.
 *
 * @param docId mã tài liệu {@code <eventId>:<thứ tự đối tượng>}, nhận trùng sự kiện thì ghi đè cùng
 *     tài liệu
 * @param self {@code true} khi là dữ liệu của chính người xem, {@code null} khi không xác định
 */
public record AuditDocument(
    String docId,
    UUID eventId,
    TenantId tenantId,
    Instant occurredAt,
    UUID actorId,
    UUID actorAssignmentId,
    String actorRole,
    String action,
    String outcome,
    String objectType,
    String objectId,
    String column,
    String purpose,
    Boolean self,
    boolean system,
    String traceparent) {

  /** Hành động kiểm toán khi xem dữ liệu C2 (CORE-00-05). */
  public static final String ACTION_C2_ACCESS = "XEM_C2";

  /** Hành động kiểm toán của chính việc tra cứu nhật ký (TL-01: ghi lại việc truy cập nhật ký). */
  public static final String ACTION_SEARCH = "XEM_NHAT_KY_KIEM_TOAN";

  public AuditDocument {
    Objects.requireNonNull(docId, "docId");
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(action, "action");
  }
}
