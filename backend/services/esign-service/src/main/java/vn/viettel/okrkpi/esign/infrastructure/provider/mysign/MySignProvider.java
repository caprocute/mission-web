package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import vn.viettel.okrkpi.esign.infrastructure.provider.csc.CscRemoteProvider;

/**
 * Nhà cung cấp MySign theo CSC API v2 (DV-10-02, TL-01 "SVC-10 esign-service"). Mở phiên: chọn
 * chứng thư của người ký ({@code credentials/list}, {@code credentials/info}), chuẩn bị vùng ký PDF
 * và thuộc tính CMS ngay tại esign, gửi băm (không gửi tài liệu) qua {@code signatures/signHash}
 * bất đồng bộ; người ký xác nhận trên ứng dụng MySign. Thăm dò tới khi có chữ ký thô, rồi dựng CMS
 * kèm dấu thời gian (PAdES-B-T), nhúng vào PDF và ghi tệp đã ký lên kho đối tượng. Trạng thái phiên
 * giữa hai bước nằm trên kho đối tượng, nên bước nào chạy ở bản sao nào cũng được.
 *
 * <p>Phần ký từ xa CSC dùng chung với Viettel-CA và CA chuyên dùng Chính phủ nằm ở {@link
 * CscRemoteProvider} (DV-10-04); MySign tin trạng thái chứng thư do máy chủ CSC trả.
 *
 * <p>Mức B-LTA (DSS với OCSP/CRL và dấu thời gian lưu trữ) bổ sung khi DV-10-03 có TSA và kho dữ
 * liệu kiểm tra chứng thư; tới lúc đó tệp đạt B-T khi có {@code tsa-url}, B-B khi không.
 */
public class MySignProvider extends CscRemoteProvider {

  public static final String ID = "mysign";

  public MySignProvider(
      CscClient csc,
      ObjectStorage storage,
      TimestampClient tsa,
      MySignProperties properties,
      ObjectMapper json,
      Clock clock) {
    super(
        new Settings(ID, "MySign", properties.signatureSize(), properties.storage().signedPrefix()),
        csc,
        storage,
        tsa,
        null,
        json,
        clock);
  }
}
