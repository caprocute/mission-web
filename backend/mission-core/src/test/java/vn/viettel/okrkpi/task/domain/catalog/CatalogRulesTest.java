package vn.viettel.okrkpi.task.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Quy tắc nhập mục danh mục (BR-04-14), mã tự sinh, gợi ý điểm và chặn vòng kế thừa. */
class CatalogRulesTest {

  private static final List<String> VAGUE = List.of("tăng cường", "kịp thời");

  @Test
  void hopLeThiKhongNem() {
    CatalogRules.validate(CatalogRules.normalize(draft(3, "30", "Thẩm định hồ sơ")), VAGUE);
  }

  @Test
  void mucKhoNgoaiMotDenNamVaDiemQuaTramBiChan() {
    assertThatThrownBy(
            () -> CatalogRules.validate(CatalogRules.normalize(draft(0, "30", "Lập hồ sơ")), VAGUE))
        .isInstanceOfSatisfying(
            CatalogViolationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(CatalogViolationException.Violation::field)
                    .containsExactly("difficulty"));
    assertThatThrownBy(
            () ->
                CatalogRules.validate(CatalogRules.normalize(draft(3, "101", "Lập hồ sơ")), VAGUE))
        .isInstanceOfSatisfying(
            CatalogViolationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(CatalogViolationException.Violation::field)
                    .containsExactly("baseScore"));
  }

  @Test
  void tenChuaTuMoHoBiChanKhongPhanBietDau() {
    assertThatThrownBy(
            () ->
                CatalogRules.validate(
                    CatalogRules.normalize(draft(3, "30", "TANG CUONG kiểm tra")), VAGUE))
        .isInstanceOf(CatalogViolationException.class)
        .hasMessageContaining("tăng cường");
    assertThat(CatalogRules.vagueWord("Báo cáo kịp thời", VAGUE)).isEqualTo("kịp thời");
    assertThat(CatalogRules.vagueWord("Tăng cườngx", VAGUE)).isNull();
  }

  @Test
  void thieuMinhChungVaMaSaiDangBiChan() {
    CatalogItemDraft d = draft(3, "30", "Lập hồ sơ");
    CatalogItemDraft bad =
        new CatalogItemDraft(
            "DV-1",
            null,
            d.name(),
            d.productName(),
            d.unit(),
            d.difficulty(),
            d.baseScore(),
            d.qualityCriteria(),
            "  ",
            d.group(),
            0,
            null);
    assertThatThrownBy(() -> CatalogRules.validate(CatalogRules.normalize(bad), VAGUE))
        .isInstanceOfSatisfying(
            CatalogViolationException.class,
            e ->
                assertThat(e.violations())
                    .extracting(CatalogViolationException.Violation::field)
                    .containsExactly("code", "evidenceRequired", "standardDays"));
  }

  @Test
  void maTuSinhTheoTienToPhoBienVaSoLonNhat() {
    assertThat(CatalogRules.nextCode(List.of("TC-DV-01", "TC-DV-07", "TC-KT-02")))
        .isEqualTo("TC-DV-08");
    assertThat(CatalogRules.nextCode(List.of())).isEqualTo("TC-CV-01");
  }

  @Test
  void goiYDiemTheoHeSoMucKho() {
    Map<Integer, BigDecimal> s =
        CatalogRules.scoreSuggestion(BigDecimal.valueOf(5), Map.of(4, new BigDecimal("1.2")));
    assertThat(s.get(3)).isEqualByComparingTo("15");
    assertThat(s.get(4)).isEqualByComparingTo("24");
    assertThat(CatalogRules.scoreSuggestion(BigDecimal.valueOf(30), Map.of()).get(5))
        .isEqualByComparingTo("100");
  }

  @Test
  void keThuaTaoVongBiChan() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    Map<UUID, UUID> parents = Map.of(b, a, c, b);
    // a kế thừa c, trong khi c → b → a: vòng.
    assertThatThrownBy(
            () -> CatalogRules.requireAcyclic(a, c, id -> Optional.ofNullable(parents.get(id))))
        .isInstanceOf(CatalogViolationException.class);
    assertThatThrownBy(() -> CatalogRules.requireAcyclic(a, a, id -> Optional.empty()))
        .isInstanceOf(CatalogViolationException.class);
    CatalogRules.requireAcyclic(c, a, id -> Optional.ofNullable(parents.get(id)));
  }

  @Test
  void boDauTiengViet() {
    assertThat(AccentFold.fold("Đánh GIÁ Ưu tiên")).isEqualTo("danh gia uu tien");
    assertThat(AccentFold.FROM).hasSameSizeAs(AccentFold.TO);
  }

  private static CatalogItemDraft draft(int difficulty, String score, String name) {
    return new CatalogItemDraft(
        null,
        null,
        name,
        "Hồ sơ",
        "Bộ",
        difficulty,
        new BigDecimal(score),
        "Đủ thành phần",
        "Biên bản thẩm định",
        "THUONG_XUYEN",
        10,
        null);
  }

  @Test
  void khungChinhDiemVaMucHeSo() {
    BigDecimal fifty = new BigDecimal("50");
    ItemScoring band =
        new ItemScoring(java.util.Map.of(), null, new BigDecimal("-0.2"), new BigDecimal("0.2"));
    assertThat(CatalogRules.adjustViolation(fifty, band, new BigDecimal("62.5"))).isNotNull();
    assertThat(CatalogRules.adjustViolation(fifty, band, new BigDecimal("40"))).isNull();
    assertThat(CatalogRules.adjustViolation(fifty, band, new BigDecimal("39.9"))).isNotNull();
    assertThat(CatalogRules.adjustViolation(fifty, ItemScoring.NONE, new BigDecimal("99")))
        .isNull();
    assertThat(CatalogRules.adjustViolation(null, band, new BigDecimal("99"))).isNull();
    ItemScoring upOnly = new ItemScoring(null, null, null, new BigDecimal("0.1"));
    assertThat(CatalogRules.adjustViolation(fifty, upOnly, new BigDecimal("49"))).isNotNull();
    assertThat(CatalogRules.adjustViolation(fifty, upOnly, new BigDecimal("55"))).isNull();

    assertThat(
            CatalogRules.scoring(
                new ItemScoring(
                    java.util.Map.of("Hoa", "x", "ok", ""),
                    "a b",
                    new BigDecimal("-2"),
                    new BigDecimal("11"))))
        .extracting(CatalogViolationException.Violation::field)
        .containsExactly("coefficientLevels", "axisCode", "scoreAdjustMin", "scoreAdjustMax");
    assertThat(CatalogRules.scoring(ItemScoring.NONE)).isEmpty();
    assertThat(
            CatalogRules.declaredLevels(
                java.util.Map.of("a", "1", "b", "2", "c", "3"),
                java.util.Map.of("a", java.util.Set.of("1"), "b", java.util.Set.of())))
        .hasSize(1);
  }

  @Test
  void docOMucHeSoTuTep() {
    java.util.List<CatalogViolationException.Violation> errors = new java.util.ArrayList<>();
    assertThat(CatalogSheet.levels("He_So_Nhom = NHOM_2; du_phong=A", errors))
        .containsEntry("he_so_nhom", "NHOM_2")
        .containsEntry("du_phong", "A");
    assertThat(CatalogSheet.levels(null, errors)).isEmpty();
    assertThat(errors).isEmpty();
    assertThat(CatalogSheet.levels("he_so_nhom", errors)).isEmpty();
    assertThat(errors).hasSize(1);
  }
}
