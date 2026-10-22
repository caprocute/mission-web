package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ca kiểm thử vàng của gói ({@code policy.golden_test_case}, TL-03): đầu vào và kết quả mong đợi
 * dạng JSON giả lập, chỉ sửa được khi còn {@link GoldenCaseStatus#NHAP} (BR-15-08).
 */
public record GoldenTestCase(
    UUID id,
    UUID packId,
    String caseCode,
    String description,
    String inputJson,
    String expectedJson,
    String legalRef,
    GoldenCaseSource source,
    GoldenCaseStatus status,
    UUID approvedBy,
    Instant approvedAt,
    byte[] signature,
    UUID createdBy,
    long rowVersion) {

  public GoldenTestCase {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(packId, "packId");
    Objects.requireNonNull(caseCode, "caseCode");
    Objects.requireNonNull(inputJson, "inputJson");
    Objects.requireNonNull(expectedJson, "expectedJson");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(status, "status");
  }

  /** Ca mới ở {@code NHAP}. */
  public static GoldenTestCase draft(
      UUID id,
      UUID packId,
      String caseCode,
      String description,
      String inputJson,
      String expectedJson,
      String legalRef,
      GoldenCaseSource source,
      UUID createdBy) {
    return new GoldenTestCase(
        id,
        packId,
        caseCode,
        description,
        inputJson,
        expectedJson,
        legalRef,
        source,
        GoldenCaseStatus.NHAP,
        null,
        null,
        null,
        createdBy,
        0);
  }

  /** Còn sửa được đầu vào và kết quả mong đợi. */
  public boolean editable() {
    return status == GoldenCaseStatus.NHAP;
  }

  /** Người ký trùng người tạo ca (bốn mắt). */
  public boolean authoredBy(UUID actor) {
    return createdBy != null && createdBy.equals(actor);
  }

  /** Ca sau khi ký duyệt. */
  public GoldenTestCase approved(UUID approver, Instant at, byte[] sig) {
    return new GoldenTestCase(
        id,
        packId,
        caseCode,
        description,
        inputJson,
        expectedJson,
        legalRef,
        source,
        GoldenCaseStatus.DA_DUYET,
        approver,
        at,
        sig,
        createdBy,
        rowVersion + 1);
  }
}
