package vn.viettel.okrkpi.product.domain.evidence;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Dựng phiên bản minh chứng mẫu cho kiểm thử. */
public final class EvidenceFixtures {

  public static final Instant T0 = Instant.parse("2027-03-10T02:00:00Z");

  private EvidenceFixtures() {}

  public static byte[] sha() {
    byte[] b = new byte[32];
    Arrays.fill(b, (byte) 7);
    return b;
  }

  /** Phiên bản ở {@code status}, đủ điều kiện nộp (có tệp, băm, quét sạch). */
  public static EvidenceVersion version(EvidenceStatus status) {
    return version(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status);
  }

  public static EvidenceVersion version(
      UUID evidenceId, UUID deliverableId, UUID taskId, EvidenceStatus status) {
    boolean submitted = status != EvidenceStatus.NHAP;
    return new EvidenceVersion(
        UUID.randomUUID(),
        evidenceId,
        deliverableId,
        taskId,
        1,
        status,
        UUID.randomUUID(),
        sha(),
        EvidenceVersion.SCAN_CLEAN,
        false,
        null,
        submitted ? UUID.randomUUID() : null,
        submitted ? T0 : null,
        status == EvidenceStatus.DA_XAC_NHAN ? T0.plusSeconds(3600) : null,
        null,
        null,
        0);
  }
}
