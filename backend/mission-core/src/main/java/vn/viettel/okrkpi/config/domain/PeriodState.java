package vn.viettel.okrkpi.config.domain;

import java.util.Optional;

/**
 * Trạng thái của kỳ đánh giá ({@code config.period.status}, baseline mục 9.1) cùng năm chuyển hợp
 * lệ. Mỗi chuyển gắn đúng một nguyên nhân ({@link PeriodChangeCause}) để ghi {@code
 * config.period_status_log.cause_type}; trigger {@code tg_period__transition} chặn lại ở CSDL.
 */
public enum PeriodState {
  MO,
  DANG_KHOA,
  DA_KHOA,
  MO_LAI_HAU_KIEM;

  /** Nguyên nhân của chuyển {@code this → target}; rỗng khi chuyển không hợp lệ. */
  public Optional<PeriodChangeCause> causeOf(PeriodState target) {
    PeriodChangeCause cause =
        switch (this) {
          case MO -> target == DANG_KHOA ? PeriodChangeCause.SAGA_CHOT_KY : null;
          case DANG_KHOA ->
              switch (target) {
                case MO -> PeriodChangeCause.BU_TRU_SAGA;
                case DA_KHOA -> PeriodChangeCause.SAGA_CHOT_KY;
                default -> null;
              };
          case DA_KHOA -> target == MO_LAI_HAU_KIEM ? PeriodChangeCause.MO_LAI_HAU_KIEM : null;
          case MO_LAI_HAU_KIEM -> target == DA_KHOA ? PeriodChangeCause.KHOA_LAI : null;
        };
    return Optional.ofNullable(cause);
  }

  public boolean canMoveTo(PeriodState target) {
    return causeOf(target).isPresent();
  }

  /**
   * Kỳ cho ghi dữ liệu nghiệp vụ: chặn khi đang khoá hoặc đã khoá (TL-01 {@code assertWritable}).
   */
  public boolean writable() {
    return this == MO || this == MO_LAI_HAU_KIEM;
  }
}
