package vn.viettel.okrkpi.integ.application.rating;

import java.util.Objects;
import java.util.UUID;

/** Phần EVT-12 {@code appraisal.rating.decided.v1} cần chia sẻ ra ngoài. */
public record RatingDecided(
    UUID profileId,
    UUID personId,
    String flow,
    String ratingCode,
    UUID deciderId,
    UUID signRequestId,
    int aggregateVersion) {

  public RatingDecided {
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(ratingCode, "ratingCode");
  }
}
