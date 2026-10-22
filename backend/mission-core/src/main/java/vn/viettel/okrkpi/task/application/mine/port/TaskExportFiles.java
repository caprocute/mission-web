package vn.viettel.okrkpi.task.application.mine.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho tệp xuất tạm thời; chỉ người tạo tác vụ đọc được tệp. */
public interface TaskExportFiles {

  void put(TenantId tenant, String jobId, UUID owner, byte[] content);

  Optional<byte[]> find(TenantId tenant, String jobId, UUID owner);
}
