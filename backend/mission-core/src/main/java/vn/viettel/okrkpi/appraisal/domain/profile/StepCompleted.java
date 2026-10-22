package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Objects;
import java.util.UUID;

/** Một bước chuyển đã chấp nhận, nguồn của EVT-11 {@code HoSoDanhGiaChuyenBuoc}. */
public record StepCompleted(
    ProfileCommand command,
    AppraisalStatus from,
    AppraisalStatus to,
    LevelKind stage,
    UUID actorId,
    String reason) {

  public StepCompleted {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actorId, "actorId");
    reason = reason == null || reason.isBlank() ? null : reason;
  }
}
