package vn.viettel.okrkpi.appraisal.application.prospect.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-11 {@code HoSoDanhGiaChuyenBuoc} khi danh sách phân nhóm chuyển bước (trình tập thể), ghi vào
 * outbox trong giao dịch của bên gọi. Không mang nhóm, lý do hay danh tính người trong danh sách
 * (BR-10-10).
 */
public interface ProspectEvents {

  void boardStepCompleted(
      TenantId tenant, UUID boardId, String from, String to, UUID actorId, long boardRowVersion);
}
