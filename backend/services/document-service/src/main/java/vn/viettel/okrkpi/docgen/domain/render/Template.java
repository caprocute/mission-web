package vn.viettel.okrkpi.docgen.domain.render;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mẫu biểu mẫu DOCX theo phiên bản ({@code docgen.template}): tệp mẫu nằm trên bucket {@code
 * templates}, băm nội dung để phát hiện tệp bị thay; kỳ cũ sinh lại đúng phiên bản cũ.
 *
 * @param subjectGroupCodes khối đối tượng áp dụng; rỗng nghĩa là mọi khối
 * @param manifestKey khoá tệp kê khai trường của mẫu trên bucket {@code templates} (DL-05,
 *     DV-11-02); {@code null} là mẫu cũ trộn theo chỗ giữ {@code ${ten}}
 * @param manifestSha256 băm SHA-256 của tệp kê khai, {@code null} khi không có kê khai
 */
public record Template(
    UUID id,
    UUID tenantId,
    String formCode,
    int versionNo,
    List<String> subjectGroupCodes,
    String objectKey,
    byte[] contentSha256,
    TemplateStatus status,
    String manifestKey,
    byte[] manifestSha256) {

  public Template {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(formCode, "formCode");
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(contentSha256, "contentSha256");
    Objects.requireNonNull(status, "status");
    if (contentSha256.length != 32) {
      throw new IllegalArgumentException("Băm tệp mẫu phải là SHA-256 (32 byte)");
    }
    if (versionNo < 1) {
      throw new IllegalArgumentException("Phiên bản mẫu bắt đầu từ 1");
    }
    subjectGroupCodes = subjectGroupCodes == null ? List.of() : List.copyOf(subjectGroupCodes);
    contentSha256 = contentSha256.clone();
    if ((manifestKey == null) != (manifestSha256 == null)) {
      throw new IllegalArgumentException("Khoá và băm tệp kê khai phải cùng có hoặc cùng vắng");
    }
    if (manifestSha256 != null && manifestSha256.length != 32) {
      throw new IllegalArgumentException("Băm tệp kê khai phải là SHA-256 (32 byte)");
    }
    manifestSha256 = manifestSha256 == null ? null : manifestSha256.clone();
  }

  /** Mẫu không có tệp kê khai trường (trộn theo chỗ giữ {@code ${ten}}). */
  public Template(
      UUID id,
      UUID tenantId,
      String formCode,
      int versionNo,
      List<String> subjectGroupCodes,
      String objectKey,
      byte[] contentSha256,
      TemplateStatus status) {
    this(
        id,
        tenantId,
        formCode,
        versionNo,
        subjectGroupCodes,
        objectKey,
        contentSha256,
        status,
        null,
        null);
  }

  /** Mẫu có tệp kê khai trường: trộn vào content control theo tag (DV-11-02, DV-11-03). */
  public boolean hasManifest() {
    return manifestKey != null;
  }

  /** Mẫu đang hiệu lực và áp dụng cho khối {@code subjectGroupCode} ({@code null} là không rõ). */
  public boolean appliesTo(String subjectGroupCode) {
    return status == TemplateStatus.HIEU_LUC && covers(subjectGroupCode);
  }

  /**
   * Phiên bản này dùng được cho kỳ đã ghim đúng phiên bản (DV-11-04): kỳ cũ sinh lại bằng mẫu đã
   * ngừng nhưng không bằng bản nháp.
   */
  public boolean pinnedFor(int pinnedVersion, String subjectGroupCode) {
    return versionNo == pinnedVersion && status != TemplateStatus.NHAP && covers(subjectGroupCode);
  }

  private boolean covers(String subjectGroupCode) {
    return subjectGroupCodes.isEmpty()
        || subjectGroupCode == null
        || subjectGroupCodes.contains(subjectGroupCode);
  }

  @Override
  public byte[] contentSha256() {
    return contentSha256.clone();
  }

  @Override
  public byte[] manifestSha256() {
    return manifestSha256 == null ? null : manifestSha256.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Template other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Template[" + formCode + " v" + versionNo + ", " + status + "]";
  }
}
