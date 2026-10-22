package vn.viettel.okrkpi.scoring.domain;

/** Loại tác vụ tính điểm ({@code scoring.score_job.job_kind}, TL-03; EVT-10 {@code jobKind}). */
public enum ScoreJobKind {
  /** Tạm tính theo yêu cầu hoặc khi minh chứng được chấp nhận. */
  TAM_TINH,
  /** Tính chính thức khi chốt kỳ. */
  CHOT_KY,
  /** Tính lại sau hậu kiểm (EVT-16). */
  TINH_LAI_HAU_KIEM,
  /** Kiểm tra tái lập định kỳ: tính lại và so băm kết quả. */
  KIEM_TRA_TAI_LAP
}
