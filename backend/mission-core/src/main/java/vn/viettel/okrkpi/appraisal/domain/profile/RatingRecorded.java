package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * Mức mới cần chèn vào {@code rating_proposal}; {@code supersededId} là dòng hiện hành cũ của cùng
 * bước cần đặt {@code is_current = false} trước khi chèn (không sửa mức tại chỗ, TL-03).
 */
public record RatingRecorded(
    CurrentRating rating, UUID supersededId, boolean differsFromPrevious, String actorRole) {

  public RatingRecorded {
    Objects.requireNonNull(rating, "rating");
    Objects.requireNonNull(actorRole, "actorRole");
  }
}
