package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.ItemScoring;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/**
 * Danh mục kế thừa bốn cấp và mức hệ số (CORE-02-27, BS-03 DC-01, DC-02): dòng tỉnh chỉnh điểm
 * chuẩn ngoài khung của dòng cha bị 422; mức hệ số lạ với gói của kỳ bị 422; dòng cũ không có mức
 * vẫn lưu được.
 */
class CatalogScoringTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();

  private InMemoryCatalogRepository repo;
  private CatalogService service;
  private WorkCatalog block;
  private WorkCatalog province;

  @BeforeEach
  void setUp() {
    repo = new InMemoryCatalogRepository();
    PeriodApi periods = mock(PeriodApi.class);
    PeriodSnapshot open =
        new PeriodSnapshot(
            PeriodId.of(period),
            tenant,
            "K",
            "Kỳ",
            LocalDate.now(),
            LocalDate.now(),
            PeriodStatus.MO);
    when(periods.findCurrent(any())).thenReturn(Optional.of(open));
    when(periods.findById(any(), any())).thenReturn(Optional.of(open));
    CoefficientTypes types =
        new CoefficientTypes(null) {
          @Override
          public Map<String, Set<String>> declared(TenantId t, UUID periodId) {
            return periodId.equals(period)
                ? Map.of("he_so_nhom", Set.of("NHOM_1", "NHOM_2"), "du_phong", Set.of())
                : Map.of();
          }
        };
    service =
        new CatalogService(
            repo, CatalogPolicy.defaults(), periods, mock(OrgDirectoryApi.class), types);
    block = catalog("KHOI-DANG", "KHOI", null);
    province = catalog("TINH-HN", "TINH", block.id());
    CatalogItem parent =
        CatalogItem.create(
            UUID.randomUUID(),
            block.id(),
            "TC-KD-01",
            draft(
                "TC-KD-01",
                "50",
                new ItemScoring(Map.of(), null, new BigDecimal("-0.2"), new BigDecimal("0.2"))));
    repo.items.put(parent.id(), parent);
  }

  @Test
  void dongKeThuaChiChinhTrongKhung() {
    assertThatThrownBy(
            () ->
                service.addItem(
                    tenant, actor, province.id(), draft("TC-KD-01", "62.5", ItemScoring.NONE)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errors().get(0).field()).isEqualTo("baseScore");
              assertThat(e.detail()).contains("40");
            });
    CatalogService.SaveResult ok =
        service.addItem(tenant, actor, province.id(), draft("TC-KD-01", "60", ItemScoring.NONE));
    assertThat(ok.item().item().baseScore()).isEqualByComparingTo("60");
    // Mục khác mã không phải dòng kế thừa, không bị khung.
    service.addItem(tenant, actor, province.id(), draft("TC-KD-02", "90", ItemScoring.NONE));
  }

  @Test
  void mucHeSoPhaiCoTrongGoiCuaKy() {
    ItemScoring good =
        new ItemScoring(Map.of("he_so_nhom", "NHOM_2", "du_phong", "X"), "TR1", null, null);
    CatalogService.SaveResult saved =
        service.addItem(tenant, actor, block.id(), draft("TC-KD-03", "30", good));
    assertThat(saved.item().item().scoring().coefficientLevels())
        .containsEntry("he_so_nhom", "NHOM_2");
    assertThat(saved.item().item().scoring().axisCode()).isEqualTo("TR1");

    for (Map<String, String> bad :
        List.of(Map.of("he_so_la", "NHOM_1"), Map.of("he_so_nhom", "NHOM_9"))) {
      assertThatThrownBy(
              () ->
                  service.addItem(
                      tenant,
                      actor,
                      block.id(),
                      draft("TC-KD-04", "30", new ItemScoring(bad, null, null, null))))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errors().get(0).field()).isEqualTo("coefficientLevels"));
    }
    assertThatThrownBy(
            () ->
                service.addItem(
                    tenant,
                    actor,
                    block.id(),
                    draft("TC-KD-05", "30", new ItemScoring(Map.of(), "tr 1", null, null))))
        .isInstanceOf(BusinessException.class);
  }

  private WorkCatalog catalog(String code, String scope, UUID parent) {
    WorkCatalog c =
        new WorkCatalog(
            UUID.randomUUID(),
            code,
            code,
            scope,
            null,
            null,
            1,
            "TU_TAO",
            CatalogStatus.NHAP,
            parent,
            0);
    repo.catalogs.put(c.id(), c);
    return c;
  }

  private static CatalogItemDraft draft(String code, String score, ItemScoring scoring) {
    return new CatalogItemDraft(
        code,
        null,
        "Thẩm định hồ sơ " + code,
        "Hồ sơ",
        "Bộ",
        3,
        new BigDecimal(score),
        "Đủ thành phần",
        "Biên bản",
        "THUONG_XUYEN",
        10,
        "AP_DUNG",
        scoring);
  }
}
