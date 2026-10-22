package vn.viettel.okrkpi.dossier.domain.meeting;

/** Trạng thái hội nghị kiểm điểm ({@code dossier.review_meeting.status}, TL-03). */
public enum MeetingStatus {
  DU_KIEN,
  DA_GUI_DU_THAO,
  DA_HOP,
  DA_LAP_BIEN_BAN,
  DA_KY;

  /** Kế hoạch còn sửa được (dời ngày, địa điểm, thành phần, lý do giữ ngày) khi chưa họp. */
  public boolean plannable() {
    return this == DU_KIEN || this == DA_GUI_DU_THAO;
  }

  /** Góp ý bị khoá khi chủ trì đã kết thúc hội nghị (suy luận: từ {@code DA_HOP} trở đi). */
  public boolean commentsLocked() {
    return !plannable();
  }
}
