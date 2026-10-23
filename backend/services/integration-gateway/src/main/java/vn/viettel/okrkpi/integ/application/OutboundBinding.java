package vn.viettel.okrkpi.integ.application;

import java.util.Map;

/**
 * SPI chiều ra: sự kiện nội bộ trên {@link #topic()} cần gửi tới kết nối {@link #connectorCode()};
 * {@link #format} chuyển nội dung sự kiện sang định dạng của hệ ngoài.
 */
public interface OutboundBinding {

  String topic();

  String connectorCode();

  /** Loại thông điệp phía hệ ngoài ({@code outbound_message.message_type}). */
  String messageType();

  byte[] format(byte[] eventPayload, Map<String, String> headers);
}
