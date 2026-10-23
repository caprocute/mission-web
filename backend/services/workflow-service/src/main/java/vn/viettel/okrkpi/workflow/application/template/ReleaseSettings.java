package vn.viettel.okrkpi.workflow.application.template;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;

/**
 * Cấu hình phát hành mẫu luồng: thứ tự cell cuốn chiếu (cấu hình, không cố định mã cell trong mã
 * nguồn), thời hạn chờ pipeline ký, chính sách thử lại của bước triển khai từng cell và ngân sách
 * câu SQL của bộ test.
 *
 * @param cells mã cell theo thứ tự triển khai
 * @param signatureTimeout thời hạn chờ pipeline báo đã ký
 * @param cellRetry thử lại bước triển khai một cell (engine không khoẻ, đang trong khung cấm)
 * @param sqlBudget ngân sách câu SQL mỗi lần hoàn thành user task (BS-01: 20)
 */
public record ReleaseSettings(
    List<String> cells, Duration signatureTimeout, RetryPolicy cellRetry, int sqlBudget) {

  public ReleaseSettings {
    cells =
        List.copyOf(
            Objects.requireNonNull(cells, "cells").stream()
                .map(String::strip)
                .filter(c -> !c.isEmpty())
                .distinct()
                .toList());
    Objects.requireNonNull(signatureTimeout, "signatureTimeout");
    Objects.requireNonNull(cellRetry, "cellRetry");
    if (sqlBudget < 1) {
      throw new IllegalArgumentException("Ngân sách câu SQL phải dương");
    }
  }
}
