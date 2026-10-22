package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc và ghi bảng quyết định kèm khoá lạc quan {@code row_version} (CORE-05-05, SCR-1502). */
public interface PolicyDecisionTableRepository {

  /** Bảng đã lưu cùng {@code row_version} dùng làm ETag. */
  record StoredTable(DecisionTable table, long rowVersion) {}

  List<StoredTable> storedTables(TenantId tenant, UUID versionId);

  Optional<StoredTable> storedTable(TenantId tenant, UUID versionId, String tableKey);

  /** Ghi đè bảng khi {@code row_version} còn bằng {@code expected}. */
  boolean replaceTable(TenantId tenant, DecisionTable table, long expected, UUID actor);

  /** Thêm bảng mới vào phiên bản nháp. */
  void appendTable(TenantId tenant, DecisionTable table, UUID actor);
}
