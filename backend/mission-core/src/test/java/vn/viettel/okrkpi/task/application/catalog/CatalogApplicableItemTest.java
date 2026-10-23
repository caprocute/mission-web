package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogRepository.VisibleItem;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;

class CatalogApplicableItemTest {

  final TenantId tenant = new TenantId(UUID.randomUUID());
  final UUID caller = UUID.randomUUID();
  final UUID position = UUID.randomUUID();
  final CatalogRepository repo = mock(CatalogRepository.class);
  final OrgDirectoryApi directory = mock(OrgDirectoryApi.class);
  final CatalogService service =
      new CatalogService(repo, CatalogPolicy.defaults(), mock(PeriodApi.class), directory);

  static VisibleItem visible(UUID id, String code) {
    UUID catalog = UUID.randomUUID();
    return new VisibleItem(
        new CatalogItem(
            id,
            catalog,
            code,
            "Việc " + code,
            "Báo cáo",
            "báo cáo",
            2,
            BigDecimal.ONE,
            "Đạt",
            "Văn bản",
            "THUONG_XUYEN",
            null,
            null,
            "AP_DUNG",
            0,
            null),
        catalog,
        null);
  }

  void positions(UUID... ids) {
    List<AssignmentView> views = new ArrayList<>();
    for (UUID id : ids) {
      views.add(
          new AssignmentView(
              UUID.randomUUID(),
              caller,
              UUID.randomUUID(),
              id,
              null,
              "CHINH",
              "CHINH",
              false,
              LocalDate.now(),
              null));
    }
    when(directory.assignmentsOf(eq(tenant), eq(caller), any())).thenReturn(views);
  }

  @Test
  void khongCoViTriThiKhongApDung() {
    positions();
    assertThat(service.applicableItem(tenant, caller, UUID.randomUUID())).isEmpty();
    positions(position);
    assertThat(service.applicableItem(tenant, caller, null)).isEmpty();
  }

  @Test
  void duyetNhieuTrangDenKhiThay() {
    positions(position);
    UUID target = UUID.randomUUID();
    List<VisibleItem> first = new ArrayList<>();
    for (int i = 0; i < CatalogService.PAGE; i++) {
      first.add(visible(UUID.randomUUID(), String.format("A%04d", i)));
    }
    when(repo.applicableItems(eq(tenant), isNull(), any(), isNull(), isNull(), isNull(), anyInt()))
        .thenReturn(first);
    when(repo.applicableItems(eq(tenant), isNull(), any(), isNull(), eq("A0199"), any(), anyInt()))
        .thenReturn(List.of(visible(target, "B0001")));
    assertThat(service.applicableItem(tenant, caller, target)).isPresent();
    assertThat(service.applicableItem(tenant, caller, UUID.randomUUID())).isEmpty();
    assertThat(service.applicableItem(tenant, caller, first.get(3).item().id())).isPresent();
  }
}
