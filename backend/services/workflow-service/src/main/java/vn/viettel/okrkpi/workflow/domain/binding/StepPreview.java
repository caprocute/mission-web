package vn.viettel.okrkpi.workflow.domain.binding;

/**
 * Một bước xem trước sinh từ mẫu và tham số ({@code WorkflowStepPreview}).
 *
 * @param stepKey mã bước ({@code buoc} của cấp, hoặc {@code CAP_<n>})
 * @param name tên bước
 * @param role vai trò baseline xử lý bước
 * @param signRequired bước có ký số
 * @param batchSign bước ký theo lô
 * @param skippable bước có điều kiện bỏ qua
 */
public record StepPreview(
    String stepKey,
    String name,
    String role,
    boolean signRequired,
    boolean batchSign,
    boolean skippable) {}
