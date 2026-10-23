package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho đợt hậu kiểm ({@code oversight.post_audit}, CORE-07-09). */
public interface PostAuditRepository {

  /** Khoá con trỏ: thời điểm mở rồi mã, giảm dần. */
  record After(Instant openedAt, UUID id) {}

  /** Bộ lọc danh sách; {@code units} rỗng là không giới hạn đơn vị (phạm vi {@code Tn}). */
  record Filter(UUID periodId, Boolean open, UUID inspectorId, List<UUID> units) {
    public Filter {
      units = units == null ? List.of() : List.copyOf(units);
    }
  }

  void insert(TenantId tenant, PostAudit audit, UUID actorId);

  Optional<PostAudit> find(TenantId tenant, UUID id);

  Optional<PostAudit> lock(TenantId tenant, UUID id);

  /** Ghi đợt đã đóng; {@code false} khi phiên bản đã đổi. */
  boolean close(TenantId tenant, PostAudit closed, int expectedVersion, UUID actorId);

  List<PostAudit> list(TenantId tenant, Filter filter, After after, int limit);

  /** Yêu cầu mở lại thuộc cùng kỳ của tenant (để gắn vào đợt khi đóng). */
  boolean reopenRequestOfPeriod(TenantId tenant, UUID reopenRequestId, UUID periodId);
}
