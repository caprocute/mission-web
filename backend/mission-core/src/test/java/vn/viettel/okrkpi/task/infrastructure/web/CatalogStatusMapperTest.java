package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.contract.tasks.model.WorkCatalogStatus;
import vn.viettel.okrkpi.task.domain.catalog.CatalogStatus;

/** Ánh xạ trạng thái phiên bản danh mục CSDL ↔ hợp đồng (CORE-02-01, CORE-02-03). */
class CatalogStatusMapperTest {

  @Test
  void anhXaHaiChieuDayDu() {
    assertThat(CatalogStatusMapper.toContract(CatalogStatus.NHAP))
        .isEqualTo(WorkCatalogStatus.NHAP);
    assertThat(CatalogStatusMapper.toContract(CatalogStatus.HIEU_LUC))
        .isEqualTo(WorkCatalogStatus.AP_DUNG);
    assertThat(CatalogStatusMapper.toContract(CatalogStatus.NGUNG))
        .isEqualTo(WorkCatalogStatus.HET_HIEU_LUC);
    for (CatalogStatus s : CatalogStatus.values()) {
      assertThat(CatalogStatusMapper.toDomain(CatalogStatusMapper.toContract(s))).isEqualTo(s);
    }
    for (WorkCatalogStatus s : WorkCatalogStatus.values()) {
      assertThat(CatalogStatusMapper.toContract(CatalogStatusMapper.toDomain(s))).isEqualTo(s);
    }
  }
}
