package vn.viettel.okrkpi.dossier.domain.dossierpackage;

/** Loại gói ({@code dossier.dossier_package.package_kind}). */
public enum PackageKind {
  QUY,
  NAM,
  DIEN_TW;

  /** Loại gói theo khoá phân vùng của kỳ: kỳ quý có số quý ở hàng đơn vị, kỳ năm bằng 0. */
  public static PackageKind ofBucket(int periodBucket) {
    return periodBucket % 10 == 0 ? NAM : QUY;
  }

  /** Ký tự loại trong mã gói {@code DP-yyyy-{Q/N}-nnnn}; gói diện TW theo năm (suy luận). */
  public char codeLetter() {
    return this == QUY ? 'Q' : 'N';
  }
}
