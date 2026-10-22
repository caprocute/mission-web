package vn.viettel.okrkpi.workflow.domain.approval;

import java.util.Optional;
import vn.viettel.okrkpi.workflow.domain.Decision;

/**
 * Ba mẫu luồng duyệt phần 1 (DV-06-03). Mã mẫu theo TL-02 và hợp đồng ({@code policies.yaml}):
 * TL-01 dùng tên ví dụ {@code tpl-appraisal-review}, TL-02a QT-04 ghi {@code duyet-ke-hoach} còn
 * SCR-1504 và hợp đồng ghi {@code duyet-ke-hoach-quy}; dùng tên của hợp đồng.
 *
 * <p>{@code processKind} là giá trị {@code loaiHoSo} của DMN {@code chon-cap-duyet} và cột {@code
 * flowable.wf_binding.process_kind}; {@code businessType} là phần loại của khoá nghiệp vụ.
 */
public enum ApprovalTemplate {
  /** QT-02 bước 6: duyệt mục tiêu và kết quả then chốt. */
  DUYET_OKR("duyet-okr", "OKR_OBJECTIVE", "OKR", "core-okr-approve", "core-okr-return"),
  /** QT-04 bước 5: duyệt kế hoạch quý. */
  DUYET_KE_HOACH_QUY(
      "duyet-ke-hoach-quy", "PLAN", "KE_HOACH_QUY", "core-plan-approve", "core-plan-return"),
  /** QT-06 bước 6: duyệt đề nghị gia hạn, điều chỉnh nhiệm vụ vượt ngưỡng. */
  DUYET_DE_NGHI(
      "duyet-de-nghi",
      "TASK_CHANGE_REQUEST",
      "DE_NGHI",
      "core-change-request-approve",
      "core-change-request-reject");

  private final String code;
  private final String businessType;
  private final String processKind;
  private final String approveTopic;
  private final String returnTopic;

  ApprovalTemplate(
      String code,
      String businessType,
      String processKind,
      String approveTopic,
      String returnTopic) {
    this.code = code;
    this.businessType = businessType;
    this.processKind = processKind;
    this.approveTopic = approveTopic;
    this.returnTopic = returnTopic;
  }

  /** Mã mẫu, cũng là khoá định nghĩa tiến trình. */
  public String code() {
    return code;
  }

  /** Loại đối tượng nghiệp vụ trong khoá {@code <businessType>:<id>}. */
  public String businessType() {
    return businessType;
  }

  /** Loại hồ sơ của DMN và {@code wf_binding.process_kind}. */
  public String processKind() {
    return processKind;
  }

  /** Chủ đề external worker áp kết quả duyệt về lõi. */
  public String approveTopic() {
    return approveTopic;
  }

  /** Chủ đề external worker áp kết quả trả lại (từ chối) về lõi. */
  public String returnTopic() {
    return returnTopic;
  }

  /** Tìm mẫu theo mã. */
  public static Optional<ApprovalTemplate> byCode(String code) {
    for (ApprovalTemplate template : values()) {
      if (template.code.equals(code)) {
        return Optional.of(template);
      }
    }
    return Optional.empty();
  }

  /** Tìm mẫu và quyết định theo chủ đề external worker. */
  public static Optional<TopicAction> byTopic(String topic) {
    for (ApprovalTemplate template : values()) {
      if (template.approveTopic.equals(topic)) {
        return Optional.of(new TopicAction(template, Decision.DUYET));
      }
      if (template.returnTopic.equals(topic)) {
        return Optional.of(new TopicAction(template, Decision.TRA_LAI));
      }
    }
    return Optional.empty();
  }

  /**
   * Chủ đề external worker đã phân giải.
   *
   * @param template mẫu
   * @param decision quyết định cần áp về lõi
   */
  public record TopicAction(ApprovalTemplate template, Decision decision) {}

}
