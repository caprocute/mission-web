package vn.viettel.okrkpi.workflow.application.stage;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Lõi báo một bước đã xong sau khi thao tác nghiệp vụ (có step-up, ký số) thành công ở API của lõi.
 *
 * @param tenantId tenant
 * @param template mẫu
 * @param businessId id đối tượng
 * @param step mã phần tử ({@code thamDinh}) hoặc mã bước ({@code THAM_DINH})
 * @param outcome kết quả bước (ví dụ {@code DUYET}, {@code TRA_LAI})
 * @param actorId người đã làm bước, có thể {@code null} (giữ người được giao)
 * @param facts dữ kiện nhỏ cho điều kiện bỏ qua cấp ({@code mucTuDeXuat}, {@code mucThamDinh})
 */
public record AdvanceStageCommand(
    UUID tenantId,
    StageTemplate template,
    UUID businessId,
    String step,
    String outcome,
    UUID actorId,
    Map<String, String> facts) {

  public AdvanceStageCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(businessId, "businessId");
    Objects.requireNonNull(step, "step");
    Objects.requireNonNull(outcome, "outcome");
    facts = facts == null ? Map.of() : Map.copyOf(facts);
  }
}
