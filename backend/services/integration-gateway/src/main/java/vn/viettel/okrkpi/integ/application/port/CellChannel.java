package vn.viettel.okrkpi.integ.application.port;

import java.util.Optional;

/** Kênh liên cell (mTLS ở lớp lưới dịch vụ): giao gói và báo kết quả nhập. */
public interface CellChannel {

  /** Gói gửi đi: phần đầu đã ký, chữ ký, khoá bọc và bản mã. */
  record Envelope(byte[] header, byte[] signature, byte[] wrappedKey, byte[] content) {}

  /** Kết quả do cell đích ký: phần thân và chữ ký. */
  record Report(byte[] payload, byte[] signature) {}

  /** Kênh không giao được (mạng, cell đích lỗi); worker thử lại. */
  final class ChannelUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ChannelUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Giao gói; rỗng khi cell đích nhận, có mã lý do khi cell đích từ chối. */
  Optional<String> deliver(CellKeyDirectory.CellKeys peer, Envelope envelope);

  void report(CellKeyDirectory.CellKeys peer, Report report);
}
