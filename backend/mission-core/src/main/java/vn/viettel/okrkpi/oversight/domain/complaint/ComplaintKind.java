package vn.viettel.okrkpi.oversight.domain.complaint;

/** Hình thức {@code oversight.complaint.kind} (BR-12-03). */
public enum ComplaintKind {
  KHIEU_NAI,
  GIAI_TRINH,
  BAO_LUU;

  /**
   * Hình thức làm phát sinh nghĩa vụ xem xét lại nên chuyển hồ sơ sang nhánh {@code KHIEU_NAI}; bảo
   * lưu ý kiến thì không (TL-02b QT-12 "Ngoại lệ", suy luận).
   */
  public boolean reopensProfile() {
    return this != BAO_LUU;
  }
}
