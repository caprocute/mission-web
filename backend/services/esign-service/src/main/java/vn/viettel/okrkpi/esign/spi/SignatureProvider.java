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

  /** Tạo phiên ký; người ký có thể phải xác nhận trên điện thoại. Phải trả nhanh (không chờ ký). */
  SignSession begin(SignRequest request);

  /** Trạng thái phiên: đang chờ, đã ký, bị từ chối, hết hạn. */
  SignStatus poll(String sessionId);

  /** Lấy chữ ký hoặc tài liệu đã ký của phiên ở trạng thái {@link SignStatus#SIGNED}. */
  List<SignedItem> collect(String sessionId);
}
