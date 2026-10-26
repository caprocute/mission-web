package vn.viettel.okrkpi.appraisal.application.unitresult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.UnitResultRow;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class UnitResultServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PERIOD = UUID.randomUUID();
  private static final Scope SCOPE = new Scope("p.org_unit_id = ?", List.of(UNIT));

  /** Kho giả: trả {@code n} dòng tăng dần, ghi lại bộ lọc và giới hạn nhận được. */
  static final class FakeRepository implements UnitResultRepository {
    final List<UnitResultRow> rows = new ArrayList<>();
    Filter seen;
    int seenLimit;

    FakeRepository(int n) {
      for (int i = 0; i < n; i++) {
        rows.add(
            new UnitResultRow(
                new UUID(0L, i + 1L),
                UUID.randomUUID(),
                "Cán bộ " + i,
                UNIT,
                "Ban Tổ chức",
                "DANG",
                "DA_KY_SO",
                UUID.randomUUID(),
                new BigDecimal("90.5"),
                "HTTNV",
                "HTTNV"));
      }
    }

    @Override
    public List<UnitResultRow> listUnitResults(TenantId tenant, Filter filter, int limit) {
      seen = filter;
      seenLimit = limit;
      return rows.subList(0, Math.min(limit, rows.size()));
    }
  }

  private static Filter filter(UUID unit, UUID period, String flow) {
    return new Filter(unit, period, flow, SCOPE, null);
  }

  @Test
  void trangDauCoConTroKhiConDongVaDocThemMotDong() {
    FakeRepository repo = new FakeRepository(5);
    UnitResultService service = new UnitResultService(repo);

    UnitResultService.Page page = service.listUnitResults(TENANT, filter(UNIT, PERIOD, "DANG"), 2);

    assertThat(repo.seenLimit).isEqualTo(3);
    assertThat(repo.seen.scope()).isSameAs(SCOPE);
    assertThat(page.rows()).hasSize(2);
    assertThat(page.nextAfter()).isEqualTo(page.rows().get(1).profileId());
  }

  @Test
  void trangCuoiKhongCoConTroVaMacDinhHaiMuoiDong() {
    FakeRepository repo = new FakeRepository(3);
    UnitResultService service = new UnitResultService(repo);

    UnitResultService.Page page = service.listUnitResults(TENANT, filter(UNIT, PERIOD, null), null);

    assertThat(repo.seenLimit).isEqualTo(UnitResultService.DEFAULT_LIMIT + 1);
    assertThat(page.rows()).hasSize(3);
    assertThat(page.nextAfter()).isNull();
  }

  @Test
  void thieuDonViKyHoacSaiLuongGioiHanTra422() {
    UnitResultService service = new UnitResultService(new FakeRepository(1));

    assertThatThrownBy(() -> service.listUnitResults(TENANT, filter(null, PERIOD, null), 10))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("đơn vị"));
    assertThatThrownBy(() -> service.listUnitResults(TENANT, filter(UNIT, null, null), 10))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("kỳ"));
    assertThatThrownBy(() -> service.listUnitResults(TENANT, filter(UNIT, PERIOD, "KHAC"), 10))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("Luồng"));
    assertThatThrownBy(() -> service.listUnitResults(TENANT, filter(UNIT, PERIOD, "NHA_NUOC"), 0))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("200"));
    assertThatThrownBy(() -> service.listUnitResults(TENANT, filter(UNIT, PERIOD, "NHA_NUOC"), 201))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new UnitResultService(null)).isInstanceOf(NullPointerException.class);
  }
}
