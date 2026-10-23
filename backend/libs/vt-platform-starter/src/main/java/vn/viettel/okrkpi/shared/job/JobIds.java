package vn.viettel.okrkpi.shared.job;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mã tác vụ dài có tiền tố theo dịch vụ ({@code <tiền tố>-<uuid>}, wiki 14 mục DV-01-04): cổng cell
 * chọn service theo tiền tố ({@code org-}, {@code wf-}, {@code score-}, {@code doc-}, {@code
 * ledger-}, {@code ana-}); mã không tiền tố là của mission-core. {@code Job.id} trong hợp đồng là
 * chuỗi nên tiền tố không đổi hợp đồng.
 */
public final class JobIds {

  private JobIds() {}

  /** {@code <prefix>-<uuid>}. */
  public static String format(String prefix, UUID id) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(id, "id");
    return prefix + "-" + id;
  }

  /**
   * Đọc mã do {@link #format} sinh; nhận cả UUID trần (mã phát ra trước khi có tiền tố). Rỗng khi
   * mã sai dạng hoặc mang tiền tố của service khác.
   */
  public static Optional<UUID> parse(String prefix, String jobId) {
    Objects.requireNonNull(prefix, "prefix");
    if (jobId == null) {
      return Optional.empty();
    }
    String raw = jobId.startsWith(prefix + "-") ? jobId.substring(prefix.length() + 1) : jobId;
    if (raw.length() != 36) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
