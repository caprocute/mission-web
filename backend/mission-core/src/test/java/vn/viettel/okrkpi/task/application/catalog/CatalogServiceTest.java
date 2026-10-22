package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository.PeriodUse;
import vn.viettel.okrkpi.task.application.catalog.CatalogService.ItemFilter;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.WorkCatalog;

/**
 * Ca sử dụng danh mục (CORE-02-03) trên kho giả trong bộ nhớ: BR-04-13 ghi vào phiên bản nháp, kế
 * thừa chỉ đọc, nhân bản, áp dụng phiên bản, chặn vòng kế thừa và lỗi 409, 422.
 */
class CatalogServiceTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final UUID position = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID lockedPeriod = UUID.randomUUID();
  private final UUID openPeriod = UUID.randomUUID();

  private InMemoryCatalogRepository repo;
  private OrgDirectoryApi directory;
  private CatalogService service;
  private WorkCatalog template;
  private WorkCatalog applied;
  private CatalogItem used;
  private CatalogItem free;
  private CatalogItem inherited;

  @BeforeEach
  void setUp() {
    repo = new InMemoryCatalogRepository();
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(any(), any()))
        .thenAnswer(
            inv -> {
              PeriodId id = inv.getArgument(1);
              PeriodStatus s =
                  id.value().equals(lockedPeriod) ? PeriodStatus.DA_KHOA : PeriodStatus.MO;
              return Optional.of(
                  new PeriodSnapshot(id, tenant, "K", "Kỳ", LocalDate.now(), LocalDate.now(), s));
            });
    directory = mock(OrgDirectoryApi.class);
    service = new CatalogService(repo, CatalogPolicy.defaults(), periods, directory);

    template = catalog("MAU", "Danh mục mẫu", null, CatalogStatus.HIEU_LUC, null);
    applied = catalog("CV", "Chuyên viên tổ chức", position, CatalogStatus.HIEU_LUC, template.id());
    inherited = item(template.id(), "TC-MT-01", "Thẩm định hồ sơ");
    used = item(applied.id(), "TC-DV-01", "Báo cáo chuyên đề");
    free = item(applied.id(), "TC-DV-02", "Rà soát hồ sơ");
    repo.uses.add(new PeriodUse(used.id(), lockedPeriod, 2));
    repo.uses.add(new PeriodUse(used.id(), openPeriod, 1));
  }

  @Test
  void docDanhSachVaMucNhinThay() {
    List<CatalogService.CatalogSummary> list = service.listCatalogs(tenant, null, null, 10);
    assertThat(list).hasSize(2);
    CatalogService.CatalogSummary child =
        list.stream().filter(s -> s.catalog().id().equals(applied.id())).findFirst().orElseThrow();
    assertThat(child.itemCount()).isEqualTo(3);
    assertThat(child.positionCount()).isEqualTo(1);
    assertThat(child.parentName()).isEqualTo("Danh mục mẫu");

    CatalogService.ItemsPage page =
        service.versionItems(
            tenant, applied.id(), new ItemFilter(null, null, null, null), null, null, 10);
    assertThat(page.total()).isEqualTo(3);
    CatalogService.ItemView first = page.fetched().get(0);
    assertThat(first.item().code()).isEqualTo("TC-DV-01");
    assertThat(first.usage().count()).isEqualTo(3);
    assertThat(first.usage().periods()).isEqualTo(2);
    assertThat(first.usage().usedInLockedPeriod()).isTrue();
    assertThat(first.positionName()).isEqualTo("Chuyên viên tổ chức");
    assertThat(page.fetched().get(2).visible().inherited()).isTrue();

    assertThat(
            service
                .versionItems(
                    tenant,
                    applied.id(),
                    new ItemFilter("RA SOAT", position, 3, "AP_DUNG"),
                    null,
                    null,
                    10)
                .total())
        .isEqualTo(1);
    assertThat(
            service
                .versionItems(
                    tenant,
                    applied.id(),
                    new ItemFilter(" ", null, null, null),
                    "TC-DV-01",
                    used.id(),
                    10)
                .fetched())
        .hasSize(2);
    assertThatThrownBy(
            () ->
                service.versionItems(
                    tenant,
                    UUID.randomUUID(),
                    new ItemFilter(null, null, null, null),
                    null,
                    null,
                    1))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void mucApDungTheoViTriNguoiGoi() {
    assertThat(service.applicableItems(tenant, actor, null, false, "ho so", null, null, 10))
        .hasSize(2);
    when(directory.assignmentsOf(any(), any(), any())).thenReturn(List.of());
    assertThat(service.applicableItems(tenant, actor, null, true, null, null, null, 10)).isEmpty();
    when(directory.assignmentsOf(any(), any(), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    actor,
                    unit,
                    position,
                    null,
                    "CHINH",
                    "CQ",
                    false,
                    null,
                    null),
                new AssignmentView(
                    UUID.randomUUID(), actor, unit, null, null, "KIEM", "CQ", false, null, null)));
    assertThat(service.applicableItems(tenant, actor, unit, true, null, null, null, 10)).hasSize(3);
  }

  @Test
  void viTriVaThamSoGoiY() {
    assertThat(service.positions(tenant, applied.id()))
        .containsEntry(position, "Chuyên viên tổ chức");
    assertThat(service.positions(tenant, template.id())).isEmpty();
    WorkCatalog empty = catalog("TRONG", "Văn thư", UUID.randomUUID(), CatalogStatus.NHAP, null);
    assertThat(service.positions(tenant, empty.id())).containsKey(empty.positionId());
    assertThat(service.summary(tenant, empty.id()).positionCount()).isEqualTo(1);
    assertThat(service.difficultyFactors(tenant).get(4)).isEqualByComparingTo("1.2");
    assertThat(service.difficultyFactors(tenant).get(1)).isEqualByComparingTo("1");
    assertThat(service.scoreSuggestion(tenant)).hasSize(5);
  }

  @Test
  void themVaoPhienBanApDungGhiVaoNhap() {
    CatalogService.SaveResult added =
        service.addItem(tenant, actor, applied.id(), draft(null, "Lập hồ sơ"));
    assertThat(added.movedToNewVersion()).isTrue();
    assertThat(added.item().item().code()).isEqualTo("TC-DV-03");
    WorkCatalog draft = added.catalog().catalog();
    assertThat(draft.status()).isEqualTo(CatalogStatus.NHAP);
    assertThat(draft.version()).isEqualTo(2);
    assertThat(repo.itemCodes(tenant, draft.id())).hasSize(3);

    CatalogService.SaveResult again =
        service.addItem(tenant, actor, draft.id(), draft("TC-DV-09", "Lập sổ"));
    assertThat(again.movedToNewVersion()).isFalse();
    assertThatThrownBy(
            () -> service.addItem(tenant, actor, draft.id(), draft("TC-DV-09", "Lập sổ")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "COMMON-422-VALIDATION"));
    repo.failNextItemWrite = true;
    assertThatThrownBy(
            () -> service.addItem(tenant, actor, draft.id(), draft("TC-DV-10", "Lập sổ")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.addItem(tenant, actor, draft.id(), draft(null, "Tăng cường lập sổ")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "COMMON-422-VALIDATION"));
  }

  @Test
  void suaMucDaDungOKyKhoaLuuVaoPhienBanMoi() {
    CatalogService.SaveResult moved =
        service.reviseItem(
            tenant, actor, applied.id(), used.id(), 0, draft(null, "Báo cáo chuyên đề quý"));
    assertThat(moved.movedToNewVersion()).isTrue();
    assertThat(moved.item().item().catalogId()).isNotEqualTo(applied.id());
    assertThat(moved.item().item().code()).isEqualTo("TC-DV-01");
    assertThat(repo.items.get(used.id()).name()).isEqualTo("Báo cáo chuyên đề");

    // Nháp đã có: sửa tiếp vào bản sao của nháp.
    CatalogService.SaveResult again =
        service.reviseItem(
            tenant, actor, applied.id(), used.id(), 0, draft(null, "Báo cáo chuyên đề năm"));
    assertThat(again.item().item().id()).isEqualTo(moved.item().item().id());
    assertThat(again.item().item().rowVersion()).isEqualTo(2);

    // Bản sao không còn trong nháp: thêm mới vào nháp.
    repo.items.remove(again.item().item().id());
    CatalogService.SaveResult inserted =
        service.reviseItem(
            tenant, actor, applied.id(), used.id(), 0, draft(null, "Báo cáo tổng hợp"));
    assertThat(inserted.movedToNewVersion()).isTrue();
    assertThat(inserted.item().item().rowVersion()).isZero();
    repo.items.remove(inserted.item().item().id());
    repo.failNextItemWrite = true;
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), used.id(), 0, draft(null, "Báo cáo")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void suaTaiChoKeThuaVaXungDot() {
    CatalogService.SaveResult inPlace =
        service.reviseItem(
            tenant,
            actor,
            applied.id(),
            free.id(),
            0,
            draft("TC-DV-05", "Rà soát hồ sơ đảng viên"));
    assertThat(inPlace.movedToNewVersion()).isFalse();
    assertThat(inPlace.item().item().code()).isEqualTo("TC-DV-05");
    assertThat(inPlace.item().item().rowVersion()).isEqualTo(1);

    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), inherited.id(), 0, draft(null, "Thẩm định")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "TASK-409-NOT-DRAFT"));
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), free.id(), 0, draft(null, "Rà soát")))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), free.id(), 1, draft("TC-DV-01", "Rà soát")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "COMMON-422-VALIDATION"));
    repo.failNextItemWrite = true;
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), free.id(), 1, draft(null, "Rà soát")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), UUID.randomUUID(), 0, draft(null, "Rà soát")))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, template.id(), used.id(), 0, draft(null, "Rà soát")))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nhanBanApDungVaPhienBanNgung() {
    CatalogService.CatalogSummary v2 = service.cloneVersion(tenant, actor, applied.id());
    assertThat(v2.catalog().version()).isEqualTo(2);
    assertThat(repo.itemCodes(tenant, v2.catalog().id()))
        .containsExactlyInAnyOrder("TC-DV-01", "TC-DV-02");
    assertThatThrownBy(() -> service.cloneVersion(tenant, actor, applied.id()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, "TASK-409-INVALID-TRANSITION"));

    assertThatThrownBy(() -> service.activate(tenant, actor, v2.catalog().id(), 7))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.activate(tenant, actor, applied.id(), 0))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, "TASK-409-INVALID-TRANSITION"));
    CatalogService.CatalogSummary active = service.activate(tenant, actor, v2.catalog().id(), 0);
    assertThat(active.catalog().status()).isEqualTo(CatalogStatus.HIEU_LUC);
    assertThat(repo.catalogs.get(applied.id()).status()).isEqualTo(CatalogStatus.NGUNG);

    assertThatThrownBy(() -> service.addItem(tenant, actor, applied.id(), draft(null, "Lập sổ")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "TASK-409-NOT-DRAFT"));
    assertThatThrownBy(
            () ->
                service.reviseItem(
                    tenant, actor, applied.id(), free.id(), 0, draft(null, "Rà soát")))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "TASK-409-NOT-DRAFT"));

    repo.failNextCatalogInsert = true;
    assertThatThrownBy(() -> service.cloneVersion(tenant, actor, active.catalog().id()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> code(e, "TASK-409-INVALID-TRANSITION"));
    CatalogService.CatalogSummary v3 = service.cloneVersion(tenant, actor, active.catalog().id());
    repo.failNextStatusChange = true;
    assertThatThrownBy(() -> service.activate(tenant, actor, v3.catalog().id(), 0))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void keThuaChanVong() {
    assertThatThrownBy(() -> service.inheritFrom(tenant, actor, template.id(), applied.id()))
        .isInstanceOfSatisfying(BusinessException.class, e -> code(e, "COMMON-422-VALIDATION"));
    WorkCatalog other = catalog("KHAC", "Danh mục khác", null, CatalogStatus.NHAP, null);
    assertThat(service.inheritFrom(tenant, actor, other.id(), template.id()).parentName())
        .isEqualTo("Danh mục mẫu");
    assertThat(service.inheritFrom(tenant, actor, other.id(), null).parentName()).isNull();
  }

  @Test
  void hangSapXepVaThamSoMacDinh() {
    assertThat(CatalogRepository.rank(CatalogStatus.HIEU_LUC)).isZero();
    assertThat(CatalogRepository.rank(CatalogStatus.NHAP)).isEqualTo(1);
    assertThat(CatalogRepository.rank(CatalogStatus.NGUNG)).isEqualTo(2);
    CatalogPolicy p = CatalogPolicy.defaults();
    assertThat(p.vagueWords(tenant)).contains("tăng cường");
    assertThat(p.basePerLevel(tenant)).isEqualByComparingTo(BigDecimal.valueOf(5));
    assertThat(new CatalogRepository.DuplicateCodeException(null)).hasMessageContaining("Mã");
  }

  private static void code(BusinessException e, String expected) {
    assertThat(e.errorCode().code()).isEqualTo(expected);
  }

  private WorkCatalog catalog(
      String code, String name, UUID pos, CatalogStatus status, UUID parent) {
    WorkCatalog c =
        new WorkCatalog(
            UUID.randomUUID(), code, name, "VI_TRI", pos, unit, 1, "TU_TAO", status, parent, 0);
    repo.catalogs.put(c.id(), c);
    return c;
  }

  private CatalogItem item(UUID catalogId, String code, String name) {
    CatalogItem i = CatalogItem.create(UUID.randomUUID(), catalogId, code, draft(code, name));
    repo.items.put(i.id(), i);
    return i;
  }

  private static CatalogItemDraft draft(String code, String name) {
    return new CatalogItemDraft(
        code,
        null,
        name,
        "Hồ sơ",
        "Bộ",
        3,
        new BigDecimal("15"),
        "Đủ thành phần",
        "Biên bản",
        "THUONG_XUYEN",
        10,
        "AP_DUNG");
  }
}
