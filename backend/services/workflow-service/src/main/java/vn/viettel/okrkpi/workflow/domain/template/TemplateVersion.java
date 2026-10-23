package vn.viettel.okrkpi.workflow.domain.template;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Một phiên bản mẫu luồng nội bộ ({@code flowable.wf_template} cùng cột bổ sung của BS-01). Mọi
 * chuyển trạng thái trả bản ghi mới; phiên bản từ {@code HIEU_LUC} trở đi không đổi nội dung, người
 * phê duyệt khác người soạn (bốn mắt).
 *
 * @param id mã phiên bản
 * @param tenantId tenant nền tảng sở hữu mẫu
 * @param templateCode mã mẫu, cũng là {@code id} của process BPMN
 * @param versionNo phiên bản, đếm từ 1 theo mã mẫu
 * @param status trạng thái
 * @param bpmnXml định nghĩa BPMN 2.0
 * @param bpmnSha256 băm SHA-256 của BPMN (32 byte)
 * @param paramSchema khai báo tham số (JSON Schema)
 * @param testSuiteRef mã commit của bộ test trong repo mẫu luồng
 * @param policyPackVersionId gói quy tắc tương ứng, có thể rỗng
 * @param flowableDeploymentId mã triển khai trên engine, có thể rỗng
 * @param approvedBy người phê duyệt phát hành
 * @param signature chữ ký cosign của gói mẫu
 * @param createdBy người soạn
 * @param updatedBy người sửa gần nhất
 * @param updatedAt thời điểm sửa gần nhất
 * @param rowVersion khoá lạc quan
 */
public record TemplateVersion(
    UUID id,
    UUID tenantId,
    String templateCode,
    int versionNo,
    TemplateStatus status,
    String bpmnXml,
    byte[] bpmnSha256,
    Map<String, Object> paramSchema,
    String testSuiteRef,
    UUID policyPackVersionId,
    String flowableDeploymentId,
    UUID approvedBy,
    byte[] signature,
    UUID createdBy,
    UUID updatedBy,
    Instant updatedAt,
    long rowVersion) {

  /** Độ dài băm SHA-256. */
  public static final int SHA256_BYTES = 32;

  public TemplateVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(templateCode, "templateCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(bpmnXml, "bpmnXml");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(updatedBy, "updatedBy");
    if (versionNo < 1) {
      throw new IllegalArgumentException("Phiên bản mẫu phải từ 1");
    }
    if (bpmnSha256 == null || bpmnSha256.length != SHA256_BYTES) {
      throw new IllegalArgumentException("Băm BPMN phải đủ 32 byte");
    }
    bpmnSha256 = bpmnSha256.clone();
    signature = signature == null ? null : signature.clone();
    paramSchema = paramSchema == null ? Map.of() : new LinkedHashMap<>(paramSchema);
  }

  /** Bản nháp mới, phiên bản {@code versionNo}. */
  public static TemplateVersion draft(
      UUID id,
      UUID tenantId,
      String templateCode,
      int versionNo,
      String bpmnXml,
      byte[] sha256,
      Map<String, Object> paramSchema,
      UUID author,
      Instant now) {
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        TemplateStatus.NHAP,
        bpmnXml,
        sha256,
        paramSchema,
        null,
        null,
        null,
        null,
        null,
        author,
        author,
        now,
        0);
  }

  @Override
  public byte[] bpmnSha256() {
    return bpmnSha256.clone();
  }

  @Override
  public byte[] signature() {
    return signature == null ? null : signature.clone();
  }

  /** Băm BPMN dạng chữ thường hệ 16 ({@code bpmnSha256} của hợp đồng). */
  public String sha256Hex() {
    return HexFormat.of().formatHex(bpmnSha256);
  }

  /** Băm trùng với nội dung đã kiểm thử. */
  public boolean sameContent(byte[] sha256) {
    return sha256 != null && Arrays.equals(bpmnSha256, sha256);
  }

  /**
   * Sửa nội dung; chỉ ở {@code NHAP} hoặc {@code DAT_KIEM_THU}, và sửa sau khi đạt kiểm thử đưa mẫu
   * về {@code NHAP} (suy luận của hợp đồng {@code updateWorkflowTemplate}).
   */
  public TemplateVersion edit(
      String newXml, byte[] newSha256, Map<String, Object> newSchema, UUID actor, Instant now) {
    requireEditable();
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        TemplateStatus.NHAP,
        newXml,
        newSha256,
        newSchema,
        testSuiteRef,
        policyPackVersionId,
        flowableDeploymentId,
        null,
        null,
        createdBy,
        actor,
        now,
        rowVersion);
  }

  /** Kiểm còn chạy test được (cùng điều kiện với sửa). */
  public void requireTestable() {
    requireEditable();
  }

  /** Bộ test đạt với đúng nội dung đã kiểm: chuyển {@code DAT_KIEM_THU}. */
  public TemplateVersion markTested(byte[] testedSha256, UUID actor, Instant now) {
    requireEditable();
    if (!sameContent(testedSha256)) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.CHUA_KIEM_THU,
          "Nội dung mẫu đã thay đổi sau lần chạy test này");
    }
    return withStatus(TemplateStatus.DAT_KIEM_THU, actor, now);
  }

  /** Đề nghị phát hành: chỉ khi {@code DAT_KIEM_THU}. */
  public TemplateVersion submit(String suiteRef, UUID actor, Instant now) {
    if (status != TemplateStatus.DAT_KIEM_THU) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.CHUA_KIEM_THU,
          "Mẫu luồng chưa đạt bộ test nên chưa đề nghị phát hành được");
    }
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        TemplateStatus.CHO_PHE_DUYET,
        bpmnXml,
        bpmnSha256,
        paramSchema,
        suiteRef,
        policyPackVersionId,
        flowableDeploymentId,
        null,
        null,
        createdBy,
        actor,
        now,
        rowVersion);
  }

  /**
   * Kiểm người phê duyệt: mẫu phải đang chờ phê duyệt và người phê duyệt khác người soạn, người sửa
   * gần nhất (người đề nghị).
   */
  public void requireApprover(UUID approver) {
    Objects.requireNonNull(approver, "approver");
    if (status != TemplateStatus.CHO_PHE_DUYET) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.KHONG_CHO_PHE_DUYET,
          "Mẫu luồng không ở trạng thái chờ phê duyệt");
    }
    if (approver.equals(createdBy) || approver.equals(updatedBy)) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.CUNG_NGUOI_SOAN,
          "Người phê duyệt phát hành phải khác người soạn mẫu");
    }
  }

  /**
   * Pipeline báo đã ký cosign: ghi chữ ký, người phê duyệt, mã commit bộ test (khi pipeline báo) và
   * chuyển {@code HIEU_LUC}.
   */
  public TemplateVersion publish(byte[] cosign, UUID approver, String suiteRef, Instant now) {
    requireApprover(approver);
    if (cosign == null || cosign.length == 0) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.THIEU_CHU_KY, "Gói mẫu chưa được pipeline ký");
    }
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        TemplateStatus.HIEU_LUC,
        bpmnXml,
        bpmnSha256,
        paramSchema,
        suiteRef == null || suiteRef.isBlank() ? testSuiteRef : suiteRef,
        policyPackVersionId,
        flowableDeploymentId,
        approver,
        cosign,
        createdBy,
        approver,
        now,
        rowVersion);
  }

  /** Bản ghi sau khi lưu với phiên bản dòng mới. */
  public TemplateVersion withRowVersion(long newRowVersion, Instant savedAt) {
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        status,
        bpmnXml,
        bpmnSha256,
        paramSchema,
        testSuiteRef,
        policyPackVersionId,
        flowableDeploymentId,
        approvedBy,
        signature,
        createdBy,
        updatedBy,
        savedAt,
        newRowVersion);
  }

  private TemplateVersion withStatus(TemplateStatus next, UUID actor, Instant now) {
    return new TemplateVersion(
        id,
        tenantId,
        templateCode,
        versionNo,
        next,
        bpmnXml,
        bpmnSha256,
        paramSchema,
        testSuiteRef,
        policyPackVersionId,
        flowableDeploymentId,
        approvedBy,
        signature,
        createdBy,
        actor,
        now,
        rowVersion);
  }

  private void requireEditable() {
    if (!status.editable()) {
      throw new TemplateRuleViolation(
          TemplateRuleViolation.Reason.BAT_BIEN,
          "Phiên bản mẫu luồng này không còn sửa được; hãy tạo phiên bản mới");
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TemplateVersion t && id.equals(t.id) && rowVersion == t.rowVersion;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, rowVersion);
  }

  @Override
  public String toString() {
    return "TemplateVersion[" + templateCode + "@" + versionNo + ", " + status + "]";
  }
}
