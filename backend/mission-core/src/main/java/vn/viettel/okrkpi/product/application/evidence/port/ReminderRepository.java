package vn.viettel.okrkpi.product.application.evidence.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho đọc minh chứng chờ nghiệm thu và sổ chống nhắc trùng của tác vụ BR-07-12 (CORE-03-08). */
public interface ReminderRepository {

  /**
   * Một phiên bản {@code DA_NOP} hoặc {@code DANG_NGHIEM_THU} có người nghiệm thu được chỉ định.
   */
  record PendingReview(
      UUID evidenceId, int versionNo, UUID periodId, UUID reviewerId, Instant submittedAt) {}

  /** Mọi phiên bản chờ nghiệm thu của tenant (đã có {@code app.tenant_id}). */
  List<PendingReview> pendingReviews(TenantId tenant);

  /**
   * Giữ chỗ một lần nhắc cho {@code recipient} trong ngày {@code day}; {@code false} khi lượt khác
   * (chạy lại, pod khác) đã giữ.
   */
  boolean claim(TenantId tenant, UUID recipient, String kind, LocalDate day);
}
