package vn.viettel.okrkpi.workflow.application.port;

/**
 * Engine Flowable của từng cell (BS-01 "Triển khai lên cell"): kiểm sức khoẻ, triển khai định nghĩa
 * mẫu và đặt lại phiên bản trước làm mặc định khi bù trừ.
 */
public interface CellEngineGateway {

  /** Engine của cell đang khoẻ để nhận triển khai hoặc để sang cell kế. */
  boolean healthy(String cellCode);

  /** Phiên bản mẫu đang là mặc định trên cell, rỗng khi cell chưa có mẫu này. */
  Integer currentVersion(String cellCode, String templateCode);

  /**
   * Triển khai định nghĩa (không gắn tenant) và đặt làm mặc định; gọi lại cùng nội dung không tạo
   * bản trùng.
   *
   * @return mã triển khai trên engine của cell
   */
  String deploy(String cellCode, String templateCode, int versionNo, String bpmnXml);

  /**
   * Đặt lại phiên bản trước làm mặc định (bù trừ).
   *
   * @param previousVersionNo phiên bản trước, rỗng khi cell chưa từng có mẫu
   * @param previousBpmnXml nội dung phiên bản trước, rỗng khi không có
   */
  void restorePrevious(
      String cellCode, String templateCode, Integer previousVersionNo, String previousBpmnXml);
}
