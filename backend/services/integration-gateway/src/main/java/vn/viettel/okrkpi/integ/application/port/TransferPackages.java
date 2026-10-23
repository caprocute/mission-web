package vn.viettel.okrkpi.integ.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng bảng {@code integ.transfer_package}: gói chuyển hồ sơ cán bộ điều động liên cell (DV-13-10,
 * TL-03 "Đồng bộ giữa các cell" luồng thứ ba). Gói đi ({@code DI}) và gói đến ({@code DEN}) dùng
 * cùng id do cell nguồn cấp.
 */
public interface TransferPackages {

  /** Chiều gói. */
  enum Direction {
    DI,
    DEN
  }

  /** Trạng thái gói ({@code ck_transfer_package__status}). */
  enum Status {
    DONG_GOI,
    DA_GUI,
    DA_NHAN,
    DA_NHAP,
    TU_CHOI
  }

  /** Một dòng; {@code manifest} là JSON danh mục phần, {@code signedHeader} là phần đầu đã ký. */
  record Row(
      UUID id,
      UUID personId,
      Direction direction,
      String peerCell,
      String peerTenantCode,
      UUID peerTenantId,
      String manifest,
      String objectKey,
      byte[] packageSha256,
      byte[] wrappedKey,
      byte[] signature,
      byte[] signedHeader,
      Status status,
      String reason,
      int attempts,
      Instant nextAttemptAt,
      Instant peerNotifiedAt,
      int rowVersion,
      Instant createdAt,
      Instant updatedAt) {}

  /** Việc đến hạn của worker, mọi tenant. */
  record Due(UUID tenantId, UUID id) {}

  /** Thay đổi trạng thái; {@code peerNotifiedAt} rỗng thì giữ giá trị đang có. */
  record Change(
      Status status, String reason, int attempts, Instant nextAttemptAt, Instant peerNotifiedAt) {}

  /** Chèn dòng mới; {@code false} khi id đã có (gửi trùng). */
  boolean insert(TenantId tenant, Row row, UUID actor);

  Optional<Row> find(TenantId tenant, UUID id);

  /** Khoá dòng để xử lý; rỗng khi không có hoặc bản sao khác đang giữ. */
  Optional<Row> lock(TenantId tenant, UUID id);

  void change(TenantId tenant, UUID id, Change change, UUID actor, Instant now);

  List<Due> due(int limit);
}
