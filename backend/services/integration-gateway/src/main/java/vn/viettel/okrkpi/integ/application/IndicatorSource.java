package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * SPI đọc giá trị hiện tại của một chỉ số chuyên môn ở hệ nguồn (HRM, ERP, HIS, quản lý trường học;
 * DV-13-08): adapter gọi đối tác theo giao thức của nó, use case {@link KrSourceValueService} lo
 * thời hạn, ngắt mạch và quy đổi lỗi.
 */
public interface IndicatorSource {

  /** Giá trị đọc được và thời điểm đo ở nguồn ({@code null} khi nguồn không ghi). */
  record Reading(BigDecimal value, Instant measuredAt) {}

  /** Mã kết nối ({@code integ.connector.connector_code}). */
  String connectorCode();

  /** Đọc chỉ số {@code externalIndicatorId}; lỗi giao thức hoặc nội dung là {@link IOException}. */
  Reading read(Connector connector, String externalIndicatorId) throws IOException;
}
