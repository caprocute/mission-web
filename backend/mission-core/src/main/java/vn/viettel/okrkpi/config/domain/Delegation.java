package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uỷ quyền khi vắng mặt ({@code config.delegation}, bảng bổ sung TL-03 theo WEB-058): duyệt thay,
 * nghiệm thu thay, check-in KR thay. Trạng thái suy ra từ {@code valid_from}, {@code valid_to} và
 * {@code ended_at}.
 */
public record Delegation(
    UUID id,
    Kind kind,
    UUID delegatorId,
    UUID delegateId,
    UUID orgUnitId,
    UUID subjectId,
    Instant validFrom,
    Instant validTo,
    String reason,
    UUID createdBy,
    Instant createdAt,
    UUID endedBy,
    Instant endedAt,
    String endReason,
    int rowVersion) {

  public enum Kind {
    DUYET_THAY,
    NGHIEM_THU_THAY,
    CHECK_IN_THAY
  }

  public enum Status {
    SAP_HIEU_LUC,
    HIEU_LUC,
    HET_HAN,
    DA_THU_HOI
  }

  public Delegation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(delegatorId, "delegatorId");
    Objects.requireNonNull(delegateId, "delegateId");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(validFrom, "validFrom");
    Objects.requireNonNull(validTo, "validTo");
  }

  public boolean ended() {
    return endedAt != null;
  }

  public Status status(Instant now) {
    if (ended()) {
      return Status.DA_THU_HOI;
    }
    if (now.isBefore(validFrom)) {
      return Status.SAP_HIEU_LUC;
    }
    return now.isBefore(validTo) ? Status.HIEU_LUC : Status.HET_HAN;
  }

  public boolean effective(Instant now) {
    return status(now) == Status.HIEU_LUC;
  }

  /** Bản sau tác vụ hết hạn (chỉ tăng {@code row_version}). */
  public Delegation expiredMarked() {
    return new Delegation(
        id,
        kind,
        delegatorId,
        delegateId,
        orgUnitId,
        subjectId,
        validFrom,
        validTo,
        reason,
        createdBy,
        createdAt,
        endedBy,
        endedAt,
        endReason,
        rowVersion + 1);
  }

  public Delegation revoked(UUID actor, Instant at, String why) {
    Instant to = at.isBefore(validTo) ? at : validTo;
    Instant from = to.isAfter(validFrom) ? validFrom : to.minusMillis(1);
    return new Delegation(
        id,
        kind,
        delegatorId,
        delegateId,
        orgUnitId,
        subjectId,
        from,
        to,
        reason,
        createdBy,
        createdAt,
        actor,
        at,
        why,
        rowVersion + 1);
  }
}
