package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tài liệu đầu vào chuẩn của DV-07-03: lọc sản phẩm, trung bình chất lượng, ngày chậm chủ quan. */
class ScoringInputTest {

  private static final UUID TASK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5c01");

  @Test
  void chiDemSanPhamDaXacNhanKhongBiChanTrung() {
    TaskInput task =
        new TaskInput(
            TASK,
            "NV-1",
            "THEN_CHOT",
            4,
            new BigDecimal("30"),
            true,
            List.of(
                product(ProductInput.CONFIRMED, "1", false, 1),
                product(ProductInput.CONFIRMED, "0.5", false, 2),
                product(ProductInput.CONFIRMED, "1", true, 5),
                product("YEU_CAU_BO_SUNG", null, false, 3)),
            List.of(
                new DelaySegment(2, 4, DelaySegment.Cause.CHU_QUAN),
                new DelaySegment(1, 1, DelaySegment.Cause.CHU_QUAN),
                new DelaySegment(5, 7, DelaySegment.Cause.KHACH_QUAN)));
    Map<String, Object> nv = task.toNhiemVu();
    assertThat(nv)
        .containsEntry("task_id", TASK.toString())
        .containsEntry("ke_hoach", 4L)
        .containsEntry("so_luong_duyet", 2L)
        .containsEntry("muc_chat_luong", 0.75)
        .containsEntry("so_lan_chinh_sua", 3L)
        .containsEntry("ngay_lam_viec_cham_chu_quan", 3L)
        .containsEntry("ngay_lich_cham_chu_quan", 5L)
        .containsEntry("diem_chuan", 30.0)
        .containsEntry("khong_hoan_thanh_chu_quan", true);
  }

  @Test
  void khongCoSanPhamThiChatLuongTheoKeHoach() {
    assertThat(task(3, List.of()).toNhiemVu()).containsEntry("muc_chat_luong", 0.0);
    assertThat(task(0, List.of()).toNhiemVu())
        .containsEntry("muc_chat_luong", 1.0)
        .containsEntry("diem_chuan", 0.0);
  }

  @Test
  void tuChoiGiaTriNgoaiMien() {
    assertThatThrownBy(() -> product(ProductInput.CONFIRMED, "0.6", false, 0))
        .hasMessageContaining("Mức chất lượng");
    assertThatThrownBy(() -> product(ProductInput.CONFIRMED, null, false, 0))
        .hasMessageContaining("đã xác nhận");
    assertThatThrownBy(() -> product("NHAP", "1", false, -1)).hasMessageContaining("không âm");
    assertThat(product(ProductInput.CONFIRMED, "0.750", false, 0).qualityLevel())
        .isEqualByComparingTo("0.75");
    assertThatThrownBy(() -> new DelaySegment(-1, 0, DelaySegment.Cause.CHU_QUAN))
        .hasMessageContaining("không âm");
    assertThatThrownBy(() -> new DelaySegment(3, 2, DelaySegment.Cause.CHU_QUAN))
        .hasMessageContaining("ngày lịch");
    assertThatThrownBy(() -> new DelaySegment(1, 2, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new TaskInput(TASK, "NV", "THEN_CHOT", -1, null, false, List.of(), List.of()))
        .hasMessageContaining("Số lượng giao");
    assertThatThrownBy(
            () ->
                new TaskInput(
                    TASK, "NV", "THEN_CHOT", 1, new BigDecimal("101"), false, List.of(), List.of()))
        .hasMessageContaining("Điểm danh mục");
    assertThatThrownBy(
            () ->
                new TaskInput(
                    TASK, "NV", "THEN_CHOT", 1, new BigDecimal("-1"), false, List.of(), List.of()))
        .hasMessageContaining("Điểm danh mục");
  }

  @Test
  void taiLieuDauVaoOnDinhVaKiemThuocTinh() {
    UUID profile = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    ScoringInput a =
        new ScoringInput(
            profile, person, "DANG", List.of(task(1, List.of())), Map.of("ty_le_O", 1.0, "a", "x"));
    ScoringInput b =
        new ScoringInput(
            profile, person, "DANG", List.of(task(1, List.of())), Map.of("a", "x", "ty_le_O", 1.0));
    assertThat(a.document().hashHex()).isEqualTo(b.document().hashHex());
    assertThat(a.variables()).containsKeys("nhiem_vu", "ty_le_O");
    assertThat(a.document().json()).contains("\"flow\":\"DANG\"");
    assertThatThrownBy(() -> new ScoringInput(profile, person, "KHAC", List.of(), Map.of()))
        .hasMessageContaining("Luồng");
    assertThatThrownBy(
            () -> new ScoringInput(profile, person, "DANG", List.of(), Map.of("nhiem_vu", 1)))
        .hasMessageContaining("trùng tên");
    assertThatThrownBy(
            () -> new ScoringInput(profile, person, "DANG", List.of(), Map.of("x", List.of())))
        .hasMessageContaining("chuỗi, số hoặc boolean");
  }

  @Test
  void vetTinhCanDuThongTinNhanDien() {
    assertThatThrownBy(
            () ->
                new ScoreTrace(
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "DANG",
                    null,
                    null,
                    List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  private static TaskInput task(int planned, List<ProductInput> products) {
    return new TaskInput(TASK, "NV-1", "THUONG_XUYEN", planned, null, false, products, List.of());
  }

  private static ProductInput product(String status, String q, boolean blocked, int revisions) {
    return new ProductInput(
        UUID.randomUUID(), status, q == null ? null : new BigDecimal(q), blocked, revisions);
  }
}
