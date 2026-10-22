package vn.viettel.okrkpi.docgen.domain.render;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mẫu biểu mẫu DOCX theo phiên bản ({@code docgen.template}): tệp mẫu nằm trên bucket {@code
 * templates}, băm nội dung để phát hiện tệp bị thay; kỳ cũ sinh lại đúng phiên bản cũ.
 *
 * @param subjectGroupCodes khối đối tượng áp dụng; rỗng nghĩa là mọi khối
 */
public record Template(
    UUID id,
    UUID tenantId,
    String formCode,
    int versionNo,
    List<String> subjectGroupCodes,
    String objectKey,
    byte[] contentSha256,
    TemplateStatus status) {

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
  }

  /** Mẫu đang hiệu lực và áp dụng cho khối {@code subjectGroupCode} ({@code null} là không rõ). */
  public boolean appliesTo(String subjectGroupCode) {
    return status == TemplateStatus.HIEU_LUC
        && (subjectGroupCodes.isEmpty()
            || subjectGroupCode == null
            || subjectGroupCodes.contains(subjectGroupCode));
  }

  @Override
  public byte[] contentSha256() {
    return contentSha256.clone();
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
