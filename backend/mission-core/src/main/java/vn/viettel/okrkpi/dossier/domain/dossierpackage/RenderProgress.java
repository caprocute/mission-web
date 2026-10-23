package vn.viettel.okrkpi.dossier.domain.dossierpackage;

/**
 * Tiến độ sinh tệp của gói (SCR-1102 "104/106 tệp xong") và trạng thái gói suy ra sau mỗi EVT-17
 * hoặc khi phát hiện tệp lỗi (QT-11 "Ngoại lệ và xử lý"): còn tệp chờ thì {@code DANG_SINH}; hết
 * tệp chờ mà còn tệp lỗi thì về {@code DANG_LAP} (giữ tệp đã sinh, MSG-11-07); đủ tệp thì {@code
 * CHO_KY}.
 */
public record RenderProgress(int total, int rendered, int failed, int signed) {

  public int pending() {
    return total - rendered - failed;
  }

  /** Trạng thái gói khi gói đang sinh; gói không có biểu mẫu nào giữ {@code DANG_LAP}. */
  public PackageStatus next() {
    if (total == 0) {
      return PackageStatus.DANG_LAP;
    }
    if (pending() > 0) {
      return PackageStatus.DANG_SINH;
    }
    return failed > 0 ? PackageStatus.DANG_LAP : PackageStatus.CHO_KY;
  }
}
