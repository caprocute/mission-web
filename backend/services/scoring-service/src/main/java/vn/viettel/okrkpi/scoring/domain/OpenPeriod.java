package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;
import java.util.UUID;

/** Kỳ chưa chốt mà SVC-07 đã tính cho một luồng, ứng viên tính lại khi có Policy Pack mới. */
public record OpenPeriod(UUID periodId, int periodBucket, String flow) {

  public OpenPeriod {
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(flow, "flow");
  }
}
