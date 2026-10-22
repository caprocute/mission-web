package vn.viettel.okrkpi.okr.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Báo workflow-service kết quả của tiến trình {@code duyet-okr} sau khi giao dịch quyết định đã
 * commit (cùng cơ chế với {@link OkrApprovalStarter}, không gọi mạng trong giao dịch). Phía
 * workflow-service phải idempotent theo (khoá nghiệp vụ {@code OKR_OBJECTIVE:<id>}, {@code
 * rowVersion}). Chưa có bean thì bỏ qua: hợp đồng {@code platform.yaml} chưa có thao tác hoàn thành
 * hay huỷ tiến trình giữa các dịch vụ.
 */
public interface OkrApprovalSignal {

  /** Quyết định của người duyệt: {@code DUYET} hoặc {@code TRA_LAI}. */
  void decided(
      TenantId tenant,
      UUID objectiveId,
      String processInstanceId,
      String decision,
      UUID reviewer,
      long rowVersion);

  /** Người soạn rút lại hoặc người duyệt huỷ: kết thúc tiến trình đang mở. */
  void cancelled(
      TenantId tenant, UUID objectiveId, String processInstanceId, UUID actor, long rowVersion);
}
