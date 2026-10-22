package vn.viettel.okrkpi.esign.spi;

import java.util.List;
import java.util.Set;

/**
 * Giao diện nhà cung cấp chữ ký số (TL-01 "SVC-10 esign-service", "Thiết kế giao diện nhà cung
 * cấp"). Mỗi nhà cung cấp (MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu) là một bản
 * hiện thực; esign-service chọn theo {@link #id()}. Khoá riêng của người ký luôn nằm ở phía nhà
 * cung cấp hoặc thiết bị người ký, không bao giờ đi qua giao diện này.
 */
public interface SignatureProvider {

  /** Mã nhà cung cấp: {@code mysign}, {@code viettel-ca}, {@code gov-ca}, {@code bcy}. */
  String id();

  Set<SignatureFormat> supportedFormats();

  /**
   * Nhà cung cấp có đang nhận yêu cầu không (ví dụ mạch chưa ngắt). Chỉ đọc trạng thái cục bộ,
   * không gọi ra ngoài, để API tạo yêu cầu trả lỗi ngay thay vì để yêu cầu chờ tới hết hạn.
   */
  default boolean available() {
    return true;
  }

  /**
   * Tạo phiên ký; người ký có thể phải xác nhận trên điện thoại. Không chờ ký. Từ DV-10-02, worker
   * thăm dò gọi thao tác này, không phải luồng xử lý API.
   *
   * @throws SignatureProviderException khi nhà cung cấp từ chối ({@code permanent}) hoặc lỗi tạm
   */
  SignSession begin(SignRequest request);

  /** Trạng thái phiên: đang chờ, đã ký, bị từ chối, hết hạn. */
  SignStatus poll(String sessionId);

  /** Lấy chữ ký hoặc tài liệu đã ký của phiên ở trạng thái {@link SignStatus#SIGNED}. */
  List<SignedItem> collect(String sessionId);
}
