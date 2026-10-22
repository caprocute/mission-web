package vn.viettel.okrkpi.evidence.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Phiên tải lên nhiều phần ({@code evidence.upload_session}). */
public record UploadSession(
    UUID id,
    UUID tenantId,
    UUID userId,
    String rgwUploadId,
    byte[] declaredSha256,
    long declaredSize,
    int partSize,
    int partsDone,
    Instant expiresAt,
    Status status,
    FileKind kind,
    String classification,
    String ownerModule,
    UUID ownerRefId,
    byte[] fileNameEnc,
    UUID dekId) {

  /** Trạng thái phiên (TL-03 {@code upload_session.status}). */
  public enum Status {
    MO,
    HOAN_TAT,
    HUY
  }

  public UploadSession {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }

  public UploadPlan plan() {
    return new UploadPlan(declaredSize, partSize);
  }

  /** Tệp C3 là bản mã đầu cuối: không kiểm kiểu, không khử trùng (ADR-06). */
  public boolean e2ee() {
    return "C3".equals(classification);
  }

  public boolean openAt(Instant now) {
    return status == Status.MO && now.isBefore(expiresAt);
  }
}
