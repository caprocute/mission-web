package vn.viettel.okrkpi.dossier.domain.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Đếm ngày làm việc trọn vẹn giữa thời điểm gửi dự thảo và ngày hội nghị (BR-11-01). */
class DraftDeadlineTest {

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  /**
   * Lịch thử: nghỉ thứ Bảy, Chủ nhật, cộng ngày nghỉ lễ; ngày làm bù được tính là ngày làm việc.
   */
  static DraftDeadline.WorkingDayCounter calendar(Set<LocalDate> holidays, Set<LocalDate> makeUp) {
    return (from, to) -> {
      long n = 0;
      for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
        boolean weekend =
            d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (makeUp.contains(d) || (!weekend && !holidays.contains(d))) {
          n++;
        }
      }
      return n;
    };
  }

  static Instant at(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  @Test
  void viDuTl02bGuiChieuThuSauHopThuNamLaBaNgay() {
    DraftDeadline.Result r =
        DraftDeadline.check(
            at("2027-01-08T16:20:00+07:00"),
            at("2027-01-14T08:00:00+07:00"),
            VN,
            DraftDeadline.REQUIRED_WORKING_DAYS,
            calendar(Set.of(), Set.of()));
    assertThat(r.workingDays()).isEqualTo(3);
    assertThat(r.passed()).isTrue();
    assertThat(r.msg()).isNull();
  }

  @Test
  void ngayNghiLeLamThieuThoiHanVaLamBuBuLai() {
    // Nghỉ lễ thứ Ba 12/01: chỉ còn 11 và 13/01.
    DraftDeadline.Result holiday =
        DraftDeadline.check(
            at("2027-01-08T16:20:00+07:00"),
            at("2027-01-14T08:00:00+07:00"),
            VN,
            3,
            calendar(Set.of(LocalDate.of(2027, 1, 12)), Set.of()));
    assertThat(holiday.workingDays()).isEqualTo(2);
    assertThat(holiday.passed()).isFalse();
    assertThat(holiday.msg()).isEqualTo(DraftDeadline.MSG_NOT_ENOUGH);
    // Làm bù thứ Bảy 09/01 bù lại ngày nghỉ.
    DraftDeadline.Result makeUp =
        DraftDeadline.check(
            at("2027-01-08T16:20:00+07:00"),
            at("2027-01-14T08:00:00+07:00"),
            VN,
            3,
            calendar(Set.of(LocalDate.of(2027, 1, 12)), Set.of(LocalDate.of(2027, 1, 9))));
    assertThat(makeUp.workingDays()).isEqualTo(3);
    assertThat(makeUp.passed()).isTrue();
  }

  @Test
  void ngayTheoGioVietNamVaKhongDemHaiNgayMoc() {
    // 18:00 UTC ngày 07/01 là 01:00 ngày 08/01 giờ Việt Nam: ngày gửi vẫn là 08/01.
    DraftDeadline.Result r =
        DraftDeadline.check(
            at("2027-01-07T18:00:00Z"),
            at("2027-01-14T08:00:00+07:00"),
            VN,
            3,
            calendar(Set.of(), Set.of()));
    assertThat(r.workingDays()).isEqualTo(3);
    // Họp ngay hôm sau hoặc trước cả ngày gửi: không có ngày nào ở giữa.
    assertThat(
            DraftDeadline.check(
                    at("2027-01-13T09:00:00+07:00"),
                    at("2027-01-14T08:00:00+07:00"),
                    VN,
                    3,
                    calendar(Set.of(), Set.of()))
                .workingDays())
        .isZero();
    assertThat(
            DraftDeadline.check(
                    at("2027-01-15T09:00:00+07:00"),
                    at("2027-01-14T08:00:00+07:00"),
                    VN,
                    3,
                    (from, to) -> -5)
                .workingDays())
        .isZero();
  }

  @Test
  void chuaGuiThiChuaDatVaKhongCanhBao() {
    DraftDeadline.Result r =
        DraftDeadline.check(
            null, at("2027-01-14T08:00:00+07:00"), VN, 3, calendar(Set.of(), Set.of()));
    assertThat(r.workingDays()).isNull();
    assertThat(r.passed()).isFalse();
    assertThat(r.msg()).isNull();
    assertThat(r.requiredWorkingDays()).isEqualTo(3);
  }

  @Test
  void trangThaiSuaKeHoachVaKhoaGopY() {
    assertThat(MeetingStatus.DU_KIEN.plannable()).isTrue();
    assertThat(MeetingStatus.DA_GUI_DU_THAO.plannable()).isTrue();
    assertThat(MeetingStatus.DA_HOP.plannable()).isFalse();
    assertThat(MeetingStatus.DA_KY.commentsLocked()).isTrue();
    assertThat(MeetingStatus.DU_KIEN.commentsLocked()).isFalse();
  }
}
