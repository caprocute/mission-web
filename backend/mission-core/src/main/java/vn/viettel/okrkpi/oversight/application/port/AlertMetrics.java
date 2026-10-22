package vn.viettel.okrkpi.oversight.application.port;

/**
 * Chỉ số kết quả xử lý cảnh báo theo dấu hiệu, để theo dõi tỷ lệ cảnh báo hợp lệ và hiệu chỉnh
 * ngưỡng (BR-14-09, CORE-07-11).
 */
@FunctionalInterface
public interface AlertMetrics {

  /**
   * @param outcome {@code XAC_NHAN_HOP_LE}, {@code GIAO_KIEM_TRA}, {@code CO_VI_PHAM} hoặc {@code
   *     KHONG_VI_PHAM}
   */
  void decided(String signalCode, String outcome);
}
