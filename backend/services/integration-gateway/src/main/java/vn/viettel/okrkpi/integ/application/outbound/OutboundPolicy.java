package vn.viettel.okrkpi.integ.application.outbound;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Chính sách gửi ra của một bộ kết nối (DV-13-02): mức phân loại được chia sẻ, cách đính chữ ký
 * JWS, có bắt buộc mTLS hay không.
 *
 * <p>Kênh liên thông chỉ mang dữ liệu {@code C0}, {@code C1} (AsyncAPI {@code
 * mission-events.asyncapi.yaml}, lược đồ {@code CloudEventsHeaders.classification}), nên {@code
 * C2}, {@code C3} không bao giờ được cấu hình; bộ kết nối chỉ có thể thu hẹp thêm, ví dụ chỉ {@code
 * C0}.
 */
public record OutboundPolicy(
    String connectorCode, Set<Classification> allowed, JwsMode jwsMode, boolean mtlsRequired) {

  /** Các mức tối đa mà kênh liên thông cho phép. */
  public static final Set<Classification> CHANNEL_CEILING =
      Set.copyOf(EnumSet.of(Classification.C0, Classification.C1));

  public OutboundPolicy {
    Objects.requireNonNull(connectorCode, "connectorCode");
    allowed =
        allowed == null || allowed.isEmpty()
            ? CHANNEL_CEILING
            : Set.copyOf(EnumSet.copyOf(allowed));
    for (Classification c : allowed) {
      if (!CHANNEL_CEILING.contains(c)) {
        throw new IllegalArgumentException(
            "Kết nối " + connectorCode + " không được cấu hình chia sẻ dữ liệu mức " + c);
      }
    }
    jwsMode = jwsMode == null ? JwsMode.DETACHED : jwsMode;
  }

  /** Mặc định: chia sẻ C0, C1, chữ ký tách, bắt buộc mTLS. */
  public static OutboundPolicy defaults(String connectorCode) {
    return new OutboundPolicy(connectorCode, null, JwsMode.DETACHED, true);
  }

  public boolean permits(Classification classification) {
    return allowed.contains(classification);
  }
}
