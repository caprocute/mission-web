package vn.viettel.okrkpi.evidence.domain;

/**
 * Kết luận quét ({@code evidence.scan_result.verdict}, TL-03) và cách hiện ra ngoài: trạng thái của
 * phiên bản ({@code scan_status}, hợp đồng {@code FileScanStatus}) và giá trị {@code verdict} của
 * sự kiện {@code evidence.object.scanned.v1}. {@code NGHI_NGO} (sai định dạng, có macro) bị chặn
 * như {@code NHIEM}.
 */
public enum ScanVerdict {
  SACH("SACH", "CLEAN"),
  NHIEM("NHIEM", "INFECTED"),
  NGHI_NGO("NHIEM", "INFECTED"),
  LOI("LOI", "ERROR");

  private final String scanStatus;
  private final String eventVerdict;

  ScanVerdict(String scanStatus, String eventVerdict) {
    this.scanStatus = scanStatus;
    this.eventVerdict = eventVerdict;
  }

  public String scanStatus() {
    return scanStatus;
  }

  public String eventVerdict() {
    return eventVerdict;
  }

  /** Tệp bị cách ly: không cấp URL tải, người nộp được báo (MSG-07-02). */
  public boolean quarantined() {
    return this == NHIEM || this == NGHI_NGO;
  }
}
