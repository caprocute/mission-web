package vn.viettel.okrkpi.workflow.application.stage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Yêu cầu khởi tạo một mẫu phần 2. Hồ sơ đánh giá, mở lại kỳ và phát hành Policy Pack do lõi gửi
 * qua cổng trong tiến trình (bộ chuyển HTTP chờ hợp đồng); khiếu nại khởi tạo từ EVT-25.
 *
 * @param tenantId tenant
 * @param template mẫu
 * @param businessId id đối tượng (hồ sơ, khiếu nại, yêu cầu mở lại, phiên bản Policy Pack)
 * @param objectVersion phiên bản đối tượng lúc khởi tạo (khoá idempotent)
 * @param flow luồng
 * @param unitId đơn vị gốc của việc giao (đơn vị người được đánh giá, đơn vị xử lý khiếu nại, đơn
 *     vị của yêu cầu mở lại, đơn vị chủ quản của Policy Pack)
 * @param title tiêu đề việc cho hộp việc
 * @param creatorId người tạo đối tượng (người khiếu nại, người tạo yêu cầu, người soạn)
 * @param subjectPersonId người được đánh giá, bắt buộc với hồ sơ đánh giá
 * @param profileId hồ sơ đánh giá liên quan (khiếu nại, mở lại kỳ), có thể {@code null}
 * @param headOfUnit người được đánh giá là người đứng đầu (điều kiện bỏ qua cấp)
 * @param periodMode {@code QUY} hoặc {@code NAM} (hồ sơ đánh giá), có thể {@code null}
 * @param participants người đã tham gia hồ sơ theo nhóm, có thể rỗng
 * @param overallDue hạn tổng thể (khiếu nại), có thể {@code null}
 */
public record StartStageCommand(
    UUID tenantId,
    StageTemplate template,
    UUID businessId,
    long objectVersion,
    ApprovalFlow flow,
    UUID unitId,
    String title,
    UUID creatorId,
    UUID subjectPersonId,
    UUID profileId,
    boolean headOfUnit,
    String periodMode,
    Map<String, List<UUID>> participants,
    Instant overallDue) {

  public StartStageCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(businessId, "businessId");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(title, "title");
    participants = participants == null ? Map.of() : Map.copyOf(participants);
  }
}
