package vn.viettel.okrkpi.policy.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc và ghi ca kiểm thử vàng kèm khoá lạc quan {@code row_version} (CORE-05-06, SCR-1503). */
public interface GoldenTestCaseRepository {

  /** Tóm tắt bộ vàng của gói: số ca đã duyệt, chờ duyệt, lần ký gần nhất. */
  record SuiteSummary(int total, int pending, Instant lastApprovedAt) {}

  Optional<GoldenTestCase> findCase(TenantId tenant, UUID id);

  Optional<GoldenTestCase> findCaseByCode(TenantId tenant, UUID packId, String caseCode);

  /** Ca sắp theo mã, sau {@code afterCode} (con trỏ). */
  List<GoldenTestCase> listCases(
      TenantId tenant, UUID packId, GoldenCaseStatus status, String q, String afterCode, int limit);

  /** Thêm ca mới; {@code false} khi mã ca đã có trong gói. */
  boolean appendCase(TenantId tenant, GoldenTestCase c);

  /** Ghi đè mô tả, đầu vào, kết quả mong đợi, căn cứ, nguồn của ca {@code NHAP}. */
  boolean replaceContent(TenantId tenant, GoldenTestCase c, long expected, UUID actor);

  /** Ghi người ký, thời điểm, chữ ký và trạng thái {@code DA_DUYET}. */
  boolean storeApproval(TenantId tenant, GoldenTestCase c, long expected);

  boolean storeStatus(TenantId tenant, UUID id, GoldenCaseStatus status, long expected, UUID actor);

  SuiteSummary summary(TenantId tenant, UUID packId);
}
