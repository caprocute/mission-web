package vn.viettel.okrkpi.okr.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu uỷ quyền check-in thay ({@code okr.kr_checkin_delegate}, bổ sung, CORE-01-12) và mã KR theo
 * mục tiêu để chuyển giá trị sang phiên bản điều chỉnh.
 */
public interface OkrCheckinDelegateRepository {

  /** Một lần giao quyền chưa thu hồi. */
  record Delegation(
      UUID id,
      UUID keyResultId,
      UUID personId,
      LocalDate validUntil,
      String reason,
      UUID grantedBy,
      Instant grantedAt) {

    /** Còn hiệu lực trong ngày {@code today}: chưa tới hạn hoặc không đặt hạn. */
    public boolean validOn(LocalDate today) {
      return validUntil == null || !validUntil.isBefore(today);
    }
  }

  /** Lần giao chưa thu hồi của KR (kể cả đã quá hạn). */
  Optional<Delegation> open(TenantId tenant, UUID keyResultId);

  void appoint(
      TenantId tenant,
      UUID keyResultId,
      UUID personId,
      LocalDate validUntil,
      String reason,
      UUID actor);

  void end(TenantId tenant, UUID delegationId, UUID actor);

  /** Tăng {@code row_version} của KR (ETag) khi đổi uỷ quyền; trả phiên bản mới. */
  long touchKeyResult(TenantId tenant, int bucket, UUID keyResultId, long rowVersion, UUID actor);

  /** Mã KR và mã dòng của mục tiêu, theo mã KR. */
  record KeyResultCode(UUID id, String code) {}

  List<KeyResultCode> keyResultCodes(TenantId tenant, int bucket, UUID objectiveId);
}
