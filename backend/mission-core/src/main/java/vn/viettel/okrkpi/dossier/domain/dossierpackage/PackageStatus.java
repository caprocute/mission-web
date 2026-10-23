package vn.viettel.okrkpi.dossier.domain.dossierpackage;

/** Trạng thái gói hồ sơ ({@code dossier.dossier_package.status}, TL-02b QT-11). */
public enum PackageStatus {
  DANG_LAP,
  DANG_SINH,
  CHO_KY,
  DA_KY,
  DA_NOP,
  DA_LUU_TRU;

  /** Gói đã ký hoặc đã nộp không đổi nội dung (BR-11-10). */
  public boolean locked() {
    return this == DA_KY || this == DA_NOP || this == DA_LUU_TRU;
  }
}
