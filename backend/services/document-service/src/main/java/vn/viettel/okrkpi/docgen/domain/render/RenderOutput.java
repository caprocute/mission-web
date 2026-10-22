package vn.viettel.okrkpi.docgen.domain.render;

import java.util.Objects;
import java.util.UUID;

/** Tệp kết quả của một tác vụ sinh ({@code docgen.render_output}). */
public record RenderOutput(
    UUID id,
    UUID tenantId,
    UUID renderJobId,
    OutputKind kind,
    String objectKey,
    byte[] pdfSha256,
    long sizeBytes,
    boolean pdfaValidated) {

  public RenderOutput {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(renderJobId, "renderJobId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(pdfSha256, "pdfSha256");
    if (pdfSha256.length != 32) {
      throw new IllegalArgumentException("Băm tệp phải là SHA-256 (32 byte)");
    }
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("Tệp kết quả rỗng");
    }
    pdfSha256 = pdfSha256.clone();
  }

  @Override
  public byte[] pdfSha256() {
    return pdfSha256.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RenderOutput other && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "RenderOutput[" + kind + ", " + objectKey + "]";
  }
}
