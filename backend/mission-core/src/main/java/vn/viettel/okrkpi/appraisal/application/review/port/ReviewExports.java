package vn.viettel.okrkpi.appraisal.application.review.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Trạng thái tác vụ xuất danh sách hồ sơ và tệp kết quả của người tạo tác vụ. */
public interface ReviewExports {

  void accepted(TenantId tenant, String jobId, UUID initiator);

  int running(TenantId tenant, UUID initiator);

  void finished(TenantId tenant, String jobId, int rows, String location);

  void failed(TenantId tenant, String jobId, String message);

  void put(TenantId tenant, String jobId, UUID owner, byte[] content);

  Optional<byte[]> file(TenantId tenant, String jobId, UUID owner);
}
