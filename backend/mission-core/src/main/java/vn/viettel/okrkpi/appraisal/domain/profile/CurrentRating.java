package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * Mức hiện hành của một bước ({@code rating_proposal} có {@code is_current}), cùng tình trạng giải
 * trình: có lý do ({@code reason_enc}) và có dòng {@code rating_explanation}.
 */
public record CurrentRating(
    UUID proposalId,
    LevelKind levelKind,
    RatingLevel rating,
    UUID actorId,
    boolean explanationRequired,
    boolean hasReason,
    boolean hasExplanation) {

  public CurrentRating {
    Objects.requireNonNull(proposalId, "proposalId");
    Objects.requireNonNull(levelKind, "levelKind");
    Objects.requireNonNull(rating, "rating");
    Objects.requireNonNull(actorId, "actorId");
  }

  /** BR-09-12: đã đủ giải trình khi mức khác mức liền kề. */
  public boolean explained() {
    return !explanationRequired || (hasReason && hasExplanation);
  }

  CurrentRating withExplanation() {
    return new CurrentRating(
        proposalId, levelKind, rating, actorId, explanationRequired, hasReason, true);
  }
}
