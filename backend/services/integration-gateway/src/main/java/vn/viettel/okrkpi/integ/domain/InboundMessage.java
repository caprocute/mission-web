package vn.viettel.okrkpi.integ.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * Thông điệp adapter nhận từ hệ ngoài, trước khi chuyển đổi: mã phía ngoài (khoá chống trùng), loại
 * và nội dung nguyên văn.
 */
public record InboundMessage(String externalMsgId, String messageType, byte[] raw) {

  static final int MAX_EXTERNAL_ID = 128;
  static final int MAX_TYPE = 48;

  public InboundMessage {
    Objects.requireNonNull(raw, "raw");
    if (externalMsgId == null
        || externalMsgId.isBlank()
        || externalMsgId.length() > MAX_EXTERNAL_ID) {
      throw new IllegalArgumentException("Mã thông điệp phía ngoài phải có từ 1 đến 128 ký tự");
    }
    if (messageType == null || messageType.isBlank() || messageType.length() > MAX_TYPE) {
      throw new IllegalArgumentException("Loại thông điệp phải có từ 1 đến 48 ký tự");
    }
    raw = raw.clone();
  }

  @Override
  public byte[] raw() {
    return raw.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof InboundMessage m
        && externalMsgId.equals(m.externalMsgId)
        && messageType.equals(m.messageType)
        && Arrays.equals(raw, m.raw);
  }

  @Override
  public int hashCode() {
    return Objects.hash(externalMsgId, messageType, Arrays.hashCode(raw));
  }

  /** Không in nội dung thông điệp ra nhật ký. */
  @Override
  public String toString() {
    return "InboundMessage[" + externalMsgId + ", " + messageType + ", " + raw.length + " byte]";
  }
}
