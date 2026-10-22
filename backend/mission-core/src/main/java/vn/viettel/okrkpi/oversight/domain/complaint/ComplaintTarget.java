package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.Objects;

/** Một cấu phần bị khiếu nại ({@code targets_json}). */
public record ComplaintTarget(String key, ComplaintStage stage, String label) {
  public ComplaintTarget {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(stage, "stage");
  }
}
