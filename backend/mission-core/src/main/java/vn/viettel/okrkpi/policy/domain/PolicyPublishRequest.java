package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Yêu cầu phát hành một phiên bản Policy Pack ({@code policy.publish_request}, CORE-05-09): kỳ hiệu
 * lực và phạm vi người soạn chọn khi gửi (QT-15, BR-15-10), quyết định của người phê duyệt.
 */
public record PolicyPublishRequest(
    UUID id,
    UUID versionId,
    UUID periodId,
    List<Scope> scopes,
    String note,
    Status status,
    UUID submittedBy,
    Instant submittedAt,
    UUID decidedBy,
    Instant decidedAt,
    String decisionReason,
    long rowVersion) {

  /** Trạng thái yêu cầu ({@code ck_publish_request__status}). */
  public enum Status {
    CHO_PHE_DUYET,
    DA_DUYET,
    TU_CHOI
  }

  /** Một phạm vi áp dụng, sinh một {@code policy_binding} khi phát hành. */
  public record Scope(String flow, UUID subjectGroupId, UUID orgUnitId) {
    public Scope {
      Objects.requireNonNull(flow, "flow");
    }
  }

  public PolicyPublishRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(submittedBy, "submittedBy");
    Objects.requireNonNull(submittedAt, "submittedAt");
    scopes = List.copyOf(scopes);
    if (scopes.isEmpty()) {
      throw new IllegalArgumentException("Cần ít nhất một phạm vi áp dụng");
    }
  }

  /** Yêu cầu mới đang chờ phê duyệt. */
  public static PolicyPublishRequest pending(
      UUID id,
      UUID versionId,
      UUID periodId,
      List<Scope> scopes,
      String note,
      UUID submittedBy,
      Instant at) {
    return new PolicyPublishRequest(
        id,
        versionId,
        periodId,
        scopes,
        note,
        Status.CHO_PHE_DUYET,
        submittedBy,
        at,
        null,
        null,
        null,
        0);
  }

  /** Ghi quyết định; chỉ yêu cầu đang chờ mới quyết được. */
  public PolicyPublishRequest decide(Status decision, UUID actor, String reason, Instant at) {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(actor, "actor");
    if (status != Status.CHO_PHE_DUYET || decision == Status.CHO_PHE_DUYET) {
      throw new PolicyTransitionException(
          "POLICY-409-NOT-PENDING", "Yêu cầu phát hành không còn chờ phê duyệt");
    }
    return new PolicyPublishRequest(
        id,
        versionId,
        periodId,
        scopes,
        note,
        decision,
        submittedBy,
        submittedAt,
        actor,
        at,
        reason,
        rowVersion + 1);
  }
}
