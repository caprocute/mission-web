package vn.viettel.okrkpi.dossier.domain.specialcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.dossier.domain.specialcase.PersonPeriodFacts.Supplement;

/**
 * Một hoặc nhiều ca cho mỗi trường hợp §1–§13 của TL-02b QT-11 trên dữ liệu tổng hợp, kể cả biên
 * (điều động đúng 2 tháng, nghỉ đúng 50% thời gian kỳ).
 */
public class SpecialCaseDetectorTest {

  public static final LocalDate Q_START = LocalDate.of(2026, 10, 1);
  public static final LocalDate Q_END = LocalDate.of(2026, 12, 31);
  public static final UUID PERSON = UUID.fromString("0192a3f4-0003-7000-8000-000000000107");
  static final UUID OLD_UNIT = UUID.fromString("0192a3f4-0001-7000-8000-0000000000a1");
  static final UUID NEW_UNIT = UUID.fromString("0192a3f4-0001-7000-8000-0000000000a2");
  static final UUID SIDE_UNIT = UUID.fromString("0192a3f4-0001-7000-8000-0000000000a3");

  public static final List<SpecialCaseRule> TABLE = table();

  /** Bảng 13 dòng như gói khối Đảng v2.0. */
  static List<SpecialCaseRule> table() {
    List<SpecialCaseRule> t = new ArrayList<>();
    t.add(rule("DIEU_DONG_DUOI_2_THANG", 1, "XEP_LOAI_TAI_DON_VI_CU", null, "nguong_thang", 2));
    t.add(rule("DIEU_DONG_TU_2_THANG", 2, "XEP_LOAI_BINH_THUONG", null, "nguong_thang", 2));
    t.add(rule("KIEM_NHIEM", 3, "XEP_LOAI_BINH_THUONG", null));
    t.add(rule("NGHI_DAI_NGAY", 4, "KHONG_XEP_LOAI", null, "ty_le_thoi_gian_toi_thieu", 0.5));
    t.add(rule("DI_HOC_BIET_PHAI", 5, "XEP_LOAI_BINH_THUONG", null));
    t.add(rule("MOI_TUYEN_DUNG_BO_NHIEM", 6, "XEP_LOAI_BINH_THUONG", null, "nguong_thang", 6));
    t.add(rule("DANG_XEM_XET_XAC_MINH", 7, "CHO_KET_LUAN", null));
    t.add(rule("BI_KY_LUAT", 8, "XEP_LOAI_BINH_THUONG", null));
    t.add(rule("DON_VI_TO_CHUC_LAI", 9, "XEP_LOAI_BINH_THUONG", null));
    t.add(
        rule(
            "NGHI_HUU_THOI_VIEC",
            10,
            "XEP_LOAI_BINH_THUONG",
            "KHONG_XEP_LOAI",
            "thoi_gian_toi_thieu_thang",
            null));
    t.add(rule("DANG_VIEN_NGOAI_HTCT", 11, "XEP_LOAI_BINH_THUONG", null));
    t.add(rule("KHONG_DU_DIEU_KIEN_MUC_DE_XUAT", 12, "XEP_LOAI_BINH_THUONG", null));
    t.add(
        rule("NGUOI_DUNG_DAU_DON_VI_KHTNV", 13, "XEP_LOAI_BINH_THUONG", null, "nguong_ty_le", 0.5));
    return t;
  }

  static SpecialCaseRule rule(
      String code, int section, String effect, String insufficient, Object... params) {
    Map<String, Object> p = new HashMap<>();
    for (int i = 0; i + 1 < params.length; i += 2) {
      p.put((String) params[i], params[i + 1]);
    }
    return new SpecialCaseRule(
        code, section, "PL 6 §" + section, "Cách xử lý §" + section, effect, insufficient, p);
  }

  static AssignmentFact a(String kind, UUID unit, boolean head, String from, String to) {
    return new AssignmentFact(
        UUID.randomUUID(),
        unit,
        kind,
        head,
        LocalDate.parse(from),
        to == null ? null : LocalDate.parse(to));
  }

  static PersonPeriodFacts quarter(Supplement s, AssignmentFact... assignments) {
    return new PersonPeriodFacts(PERSON, Q_START, Q_END, List.of(assignments), s);
  }

  static AssignmentFact longSeat() {
    return a("CHINH", OLD_UNIT, false, "2020-01-01", null);
  }

  static List<String> codes(PersonPeriodFacts f) {
    return SpecialCaseDetector.detect(TABLE, f).stream().map(Detection::code).toList();
  }

  static Detection only(PersonPeriodFacts f, String code) {
    return SpecialCaseDetector.detect(TABLE, f).stream()
        .filter(d -> d.code().equals(code))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void binhThuongKhongCoTruongHopNao() {
    assertThat(codes(quarter(null, longSeat()))).isEmpty();
    assertThat(codes(quarter(Supplement.NONE))).isEmpty();
  }

  @Test
  void muc1DieuDongDuoiHaiThangGiuDonViCu() {
    PersonPeriodFacts f =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-14"),
            a("DIEU_DONG", NEW_UNIT, false, "2026-11-15", null));
    assertThat(codes(f)).containsExactly("DIEU_DONG_DUOI_2_THANG");
    Detection d = only(f, "DIEU_DONG_DUOI_2_THANG");
    assertThat(d.effect()).isEqualTo("XEP_LOAI_TAI_DON_VI_CU");
    assertThat(d.effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 15));
    assertThat(d.effectiveTo()).isEqualTo(Q_END);
    assertThat(d.detail())
        .containsEntry("donViCu", OLD_UNIT.toString())
        .containsEntry("donViMoi", NEW_UNIT.toString())
        .containsEntry("soThangTaiDonViMoi", 1L);
    assertThat(d.rule().section()).isEqualTo(1);
  }

  @Test
  void muc2DieuDongDungHaiThangLaTuHaiThangTroLen() {
    PersonPeriodFacts exact =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-10-31"),
            a("CHINH", NEW_UNIT, false, "2026-11-01", null));
    assertThat(codes(exact)).containsExactly("DIEU_DONG_TU_2_THANG");
    assertThat(only(exact, "DIEU_DONG_TU_2_THANG").detail())
        .containsEntry("soThangTaiDonViMoi", 2L);

    PersonPeriodFacts dayLess =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-01"),
            a("CHINH", NEW_UNIT, false, "2026-11-02", null));
    assertThat(codes(dayLess)).containsExactly("DIEU_DONG_DUOI_2_THANG");
  }

  @Test
  void chuyenCungDonViHoacBatDauDauKyKhongPhaiDieuDong() {
    assertThat(
            codes(
                quarter(
                    null,
                    a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-14"),
                    a("CHINH", OLD_UNIT, true, "2026-11-15", null))))
        .doesNotContain("DIEU_DONG_DUOI_2_THANG", "DIEU_DONG_TU_2_THANG");
    assertThat(
            codes(
                quarter(
                    null,
                    a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-09-30"),
                    a("CHINH", NEW_UNIT, false, "2026-10-01", null))))
        .isEmpty();
  }

  @Test
  void muc3KiemNhiem() {
    PersonPeriodFacts f =
        quarter(null, longSeat(), a("KIEM_NHIEM", SIDE_UNIT, false, "2026-11-01", "2027-03-31"));
    assertThat(codes(f)).containsExactly("KIEM_NHIEM");
    Detection d = only(f, "KIEM_NHIEM");
    assertThat(d.effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(d.effectiveTo()).isEqualTo(Q_END);
    assertThat(d.detail().get("donViKiemNhiem")).isEqualTo(List.of(SIDE_UNIT.toString()));
  }

  @Test
  void muc4NghiTrenMotNuaKyKhongXepLoaiDungMotNuaThiKhong() {
    PersonPeriodFacts f =
        quarter(supplement(47, null, null, null, null, null, null, null), longSeat());
    assertThat(codes(f)).containsExactly("NGHI_DAI_NGAY");
    assertThat(only(f, "NGHI_DAI_NGAY").effect()).isEqualTo("KHONG_XEP_LOAI");
    assertThat(only(f, "NGHI_DAI_NGAY").detail()).containsEntry("soNgayCuaKy", 92L);
    assertThat(codes(quarter(supplement(46, null, null, null, null, null, null, null), longSeat())))
        .isEmpty();
  }

  @Test
  void muc5BietPhaiHoacDiHoc() {
    assertThat(
            codes(quarter(null, longSeat(), a("BIET_PHAI", SIDE_UNIT, false, "2026-06-01", null))))
        .containsExactly("DI_HOC_BIET_PHAI");
    PersonPeriodFacts study =
        quarter(
            new Supplement(null, true, null, null, null, null, null, null, null, null), longSeat());
    assertThat(only(study, "DI_HOC_BIET_PHAI").detail()).containsEntry("loai", "DI_HOC_DAI_HAN");
  }

  @Test
  void muc6MoiTuyenDungHoacMoiBoNhiem() {
    PersonPeriodFacts recruit = quarter(null, a("CHINH", NEW_UNIT, false, "2026-08-01", null));
    Detection d = only(recruit, "MOI_TUYEN_DUNG_BO_NHIEM");
    assertThat(d.detail()).containsEntry("loai", "TUYEN_DUNG").containsEntry("soThang", 5L);
    assertThat(d.effectiveFrom()).isEqualTo(Q_START);

    PersonPeriodFacts sixMonths = quarter(null, a("CHINH", NEW_UNIT, false, "2026-07-01", null));
    assertThat(codes(sixMonths)).isEmpty();

    PersonPeriodFacts appointed =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-10-31"),
            a("CHINH", OLD_UNIT, true, "2026-11-01", null));
    assertThat(only(appointed, "MOI_TUYEN_DUNG_BO_NHIEM").detail())
        .containsEntry("loai", "BO_NHIEM");

    PersonPeriodFacts reHead =
        quarter(
            null,
            a("CHINH", OLD_UNIT, true, "2020-01-01", "2026-10-31"),
            a("CHINH", NEW_UNIT, true, "2026-11-01", null));
    assertThat(codes(reHead)).doesNotContain("MOI_TUYEN_DUNG_BO_NHIEM");
  }

  @Test
  void muc7DangXacMinhTamTreo() {
    PersonPeriodFacts f =
        quarter(
            new Supplement(null, null, true, null, null, null, null, null, null, null), longSeat());
    Detection d = only(f, "DANG_XEM_XET_XAC_MINH");
    assertThat(d.effect()).isEqualTo("CHO_KET_LUAN");
    assertThat(d.effectiveTo()).isNull();
  }

  @Test
  void muc8KyLuatTrongKy() {
    PersonPeriodFacts f =
        quarter(
            new Supplement(
                null,
                null,
                null,
                LocalDate.of(2026, 11, 20),
                "KHIEN_TRACH",
                null,
                null,
                null,
                null,
                null),
            longSeat());
    assertThat(only(f, "BI_KY_LUAT").detail()).containsEntry("hinhThuc", "KHIEN_TRACH");
    PersonPeriodFacts before =
        quarter(
            new Supplement(
                null, null, null, LocalDate.of(2026, 9, 30), null, null, null, null, null, null),
            longSeat());
    assertThat(codes(before)).isEmpty();
    PersonPeriodFacts noForm =
        quarter(
            new Supplement(null, null, null, Q_END, null, null, null, null, null, null),
            longSeat());
    assertThat(only(noForm, "BI_KY_LUAT").detail()).doesNotContainKey("hinhThuc");
  }

  @Test
  void muc9DonViToChucLai() {
    PersonPeriodFacts f =
        quarter(
            new Supplement(null, null, null, null, null, Q_START, null, null, null, null),
            longSeat());
    assertThat(only(f, "DON_VI_TO_CHUC_LAI").effectiveFrom()).isEqualTo(Q_START);
  }

  @Test
  void muc10NghiHuuChuaCoNguongThiXepLoaiBinhThuong() {
    PersonPeriodFacts f = quarter(null, a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-30"));
    Detection d = only(f, "NGHI_HUU_THOI_VIEC");
    assertThat(d.effect()).isEqualTo("XEP_LOAI_BINH_THUONG");
    assertThat(d.detail())
        .containsEntry("ngayNghi", "2026-12-01")
        .containsEntry("soThangCongTac", 2L)
        .containsEntry("nguongToiThieu", "CHUA_QUY_DINH");
    assertThat(d.effectiveTo()).isEqualTo(LocalDate.of(2026, 11, 30));
  }

  @Test
  void muc10KhongDuThoiGianToiThieuThiKhongXepLoai() {
    List<SpecialCaseRule> table =
        List.of(
            rule(
                "NGHI_HUU_THOI_VIEC",
                10,
                "XEP_LOAI_BINH_THUONG",
                "KHONG_XEP_LOAI",
                "thoi_gian_toi_thieu_thang",
                3));
    PersonPeriodFacts shortly =
        quarter(null, a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-30"));
    assertThat(SpecialCaseDetector.detect(table, shortly).get(0).effect())
        .isEqualTo("KHONG_XEP_LOAI");
    PersonPeriodFacts transferred =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-30"),
            a("CHINH", NEW_UNIT, false, "2026-12-01", null));
    assertThat(SpecialCaseDetector.detect(table, transferred)).isEmpty();
  }

  @Test
  void muc11Va12TheoDuKienBoSung() {
    PersonPeriodFacts f =
        quarter(
            new Supplement(null, null, null, null, null, null, true, true, null, null), longSeat());
    assertThat(codes(f)).containsExactly("DANG_VIEN_NGOAI_HTCT", "KHONG_DU_DIEU_KIEN_MUC_DE_XUAT");
  }

  @Test
  void muc13NguoiDungDauTrenMotNuaDonViKhongHoanThanh() {
    AssignmentFact head = a("CHINH", OLD_UNIT, true, "2020-01-01", null);
    PersonPeriodFacts f =
        quarter(new Supplement(null, null, null, null, null, null, null, null, 4, 3), head);
    assertThat(only(f, "NGUOI_DUNG_DAU_DON_VI_KHTNV").detail())
        .containsEntry("soDonViKhongHoanThanh", 3);
    PersonPeriodFacts half =
        quarter(new Supplement(null, null, null, null, null, null, null, null, 4, 2), head);
    assertThat(codes(half)).isEmpty();
    PersonPeriodFacts notHead =
        quarter(new Supplement(null, null, null, null, null, null, null, null, 4, 3), longSeat());
    assertThat(codes(notHead)).isEmpty();
    PersonPeriodFacts noUnits =
        quarter(new Supplement(null, null, null, null, null, null, null, null, 0, 0), head);
    assertThat(codes(noUnits)).isEmpty();
  }

  @Test
  void maLaVaBoQuaVaThamSoThieuDungMacDinh() {
    List<SpecialCaseRule> table =
        List.of(
            new SpecialCaseRule("MA_MOI", 14, "PL 6 §14", "", "BAO_LUU", null, null),
            new SpecialCaseRule("DIEU_DONG_DUOI_2_THANG", 1, "PL 6 §1", "", "BAO_LUU", null, null));
    PersonPeriodFacts f =
        quarter(
            null,
            a("CHINH", OLD_UNIT, false, "2020-01-01", "2026-11-14"),
            a("CHINH", NEW_UNIT, false, "2026-11-15", "2026-12-20"));
    List<Detection> found = SpecialCaseDetector.detect(table, f);
    assertThat(found).extracting(Detection::code).containsExactly("DIEU_DONG_DUOI_2_THANG");
    assertThat(found.get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 20));
    assertThat(SpecialCaseDetector.supports("MA_MOI")).isFalse();
    assertThat(SpecialCaseDetector.supports("KIEM_NHIEM")).isTrue();
    assertThat(table.get(0).number("x")).isNull();
    assertThat(table.get(0).params()).isEmpty();
  }

  @Test
  void duKienKiemRang() {
    assertThatThrownBy(() -> new AssignmentFact(null, OLD_UNIT, "CHINH", false, Q_START, null))
        .isInstanceOf(NullPointerException.class);
    PersonPeriodFacts f = new PersonPeriodFacts(PERSON, Q_START, Q_END, null, null);
    assertThat(f.assignments()).isEmpty();
    assertThat(f.supplement()).isSameAs(Supplement.NONE);
    assertThat(f.periodDays()).isEqualTo(92);
    Detection d = new Detection(TABLE.get(0), "BAO_LUU", null, null, null);
    assertThat(d.detail()).isEmpty();
    AssignmentFact seat = a("TAM_GIU", OLD_UNIT, false, "2026-01-01", "2026-01-31");
    assertThat(seat.seat()).isFalse();
    assertThat(seat.covers(LocalDate.of(2026, 2, 1))).isFalse();
    assertThat(seat.overlaps(Q_START, Q_END)).isFalse();
  }

  static Supplement supplement(
      Integer leave,
      Boolean study,
      Boolean investigation,
      LocalDate discipline,
      LocalDate reorganized,
      Boolean outside,
      Boolean ineligible,
      Integer units) {
    return new Supplement(
        leave,
        study,
        investigation,
        discipline,
        null,
        reorganized,
        outside,
        ineligible,
        units,
        null);
  }
}
