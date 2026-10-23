package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DV-13-10 · Quá trình công tác của cán bộ cho gói chuyển liên cell: đọc ở cell nguồn ({@code org.assignment} cùng
 * đơn vị, chức danh, quyết định tại thời điểm phân công) và ghi ở cell đích ({@code org.transferred_work_history}).
 * Tenant lấy từ ngữ cảnh giao dịch.
 */
public interface WorkHistoryTransferStore {

  /** Một phân công trong lý lịch công tác; {@code validTo} rỗng là còn hiệu lực. */
  record Entry(String unitCode, String unitName, String jobTitleCode, String jobTitleName, String kind, String branch,
      boolean head, LocalDate validFrom, LocalDate validTo, String orderNo, String signedDocRef) {
  }

  /** Phần đã kiểm của gói, nội dung JSON giữ nguyên. */
  record Imported(UUID id, UUID packageId, String partName, String sourceTenantCode, UUID sourcePersonId,
      String content, byte[] contentSha256) {
  }

  List<Entry> history(UUID personId);

  Optional<byte[]> importedSha256(UUID packageId, String partName);

  /** {@code false} khi phần này của gói đã có. */
  boolean insertImported(Imported row, UUID actor);
}
