package vn.viettel.okrkpi.config.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng uỷ quyền khi vắng mặt ({@code config.delegation}, CORE-08-16) cho MOD-01, MOD-03 và luồng
 * duyệt: kiểm người làm thay có uỷ quyền đang hiệu lực không. Kết quả chỉ để phản hồi nhanh và ghi
 * nhãn "duyệt thay", "nghiệm thu thay", "check-in thay"; quyết định cuối vẫn ở OPA (BR-01-18).
 */
public interface DelegationApi {

  enum Kind {
    DUYET_THAY,
    NGHIEM_THU_THAY,
    CHECK_IN_THAY
  }

  /** Một uỷ quyền đang hiệu lực. {@code subjectId} rỗng là mọi phiếu của người uỷ quyền. */
  record DelegationGrant(
      UUID id,
      Kind kind,
      UUID delegatorId,
      UUID delegateId,
      UUID orgUnitId,
      UUID subjectId,
      Instant validFrom,
      Instant validTo) {}

  /**
   * Uỷ quyền đang hiệu lực tại {@code at} cho {@code delegateId} làm thay {@code delegatorId} tại
   * đơn vị {@code orgUnitId}; {@code subjectId} (KR) chỉ dùng với {@link Kind#CHECK_IN_THAY}.
   */
  Optional<DelegationGrant> activeFor(
      TenantId tenant,
      Kind kind,
      UUID delegatorId,
      UUID delegateId,
      UUID orgUnitId,
      UUID subjectId,
      Instant at);

  /** Các uỷ quyền {@code delegateId} đang nhận tại {@code at}. */
  List<DelegationGrant> receivedBy(TenantId tenant, Kind kind, UUID delegateId, Instant at);
}
