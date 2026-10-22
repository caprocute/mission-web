package vn.viettel.okrkpi.workflow.application.approval;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/**
 * Yêu cầu khởi tạo luồng duyệt do module lõi gửi khi một đối tượng chuyển {@code CHO_DUYET} (MOD-01
 * trình OKR, MOD-02 gửi duyệt kế hoạch hoặc đề nghị vượt ngưỡng). Chỉ mang id, mã và tham số nhỏ;
 * dữ liệu nghiệp vụ ở lõi (TL-01 G2).
 *
 * @param tenantId tenant
 * @param template mẫu luồng
 * @param businessId id đối tượng (khoá nghiệp vụ {@code <businessType>:<id>})
 * @param objectVersion {@code row_version} của đối tượng lúc trình; khoá idempotent cùng khoá
 *     nghiệp vụ và là {@code If-Match} khi worker gọi lại lõi
 * @param flow luồng đánh giá
 * @param subjectUnitId đơn vị của đối tượng
 * @param headOfUnit người trình là người đứng đầu đơn vị
 * @param initiatorId người trình (dùng cho tách vai trò), có thể {@code null}
 * @param periodId kỳ, có thể {@code null}
 * @param title tiêu đề việc cho hộp việc (C0/C1)
 * @param policyPackVersion phiên bản Policy Pack áp dụng, có thể {@code null}
 * @param planVersionNo số phiên bản kế hoạch, bắt buộc với {@code duyet-ke-hoach-quy}
 * @param taskId nhiệm vụ của đề nghị, bắt buộc với {@code duyet-de-nghi}
 * @param causeClaimed nguyên nhân người gửi nêu ({@code KHACH_QUAN}, {@code CHU_QUAN}), bắt buộc
 *     với {@code duyet-de-nghi}
 */
public record StartApprovalCommand(
    UUID tenantId,
    ApprovalTemplate template,
    UUID businessId,
    long objectVersion,
    ApprovalFlow flow,
    UUID subjectUnitId,
    boolean headOfUnit,
    UUID initiatorId,
    UUID periodId,
    String title,
    String policyPackVersion,
    Integer planVersionNo,
    UUID taskId,
    String causeClaimed) {

  public StartApprovalCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(businessId, "businessId");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(subjectUnitId, "subjectUnitId");
    Objects.requireNonNull(title, "title");
  }
}
