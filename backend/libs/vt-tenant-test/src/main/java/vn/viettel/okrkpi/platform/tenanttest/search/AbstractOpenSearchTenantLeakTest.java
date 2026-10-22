package vn.viettel.okrkpi.platform.tenanttest.search;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * NEN-035: lớp cơ sở mà kiểm thử tìm kiếm của DV-16 bắt buộc kế thừa. Lớp con dựng (hoặc trỏ tới)
 * chỉ mục có dữ liệu mồi của hai tenant, rồi khai báo alias theo quy ước của TL-03 ({@code
 * t_<tenant>_task_v<n>} ở silo/bridge, alias lọc trên {@code pool_task_v<n>} ở pool).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractOpenSearchTenantLeakTest {

  protected abstract URI openSearchEndpoint();

  /** Tên alias mà SVC-16 dùng khi truy vấn cho {@code tenant}. */
  protected abstract String aliasOf(String tenant);

  protected abstract String tenantA();

  protected abstract String tenantB();

  /** Mã tài liệu đã đánh chỉ mục của {@code tenant} (dùng cho truy vấn {@code ids}). */
  protected abstract List<String> documentIdsOf(String tenant);

  protected OpenSearchTenantLeakProbe probe() {
    return new OpenSearchTenantLeakProbe(openSearchEndpoint());
  }

  @Test
  void aliasCuaAKhongTraTaiLieuCuaB() {
    probe().assertAliasIsolated(aliasOf(tenantA()), tenantA(), tenantB(), documentIdsOf(tenantB()));
  }

  @Test
  void aliasCuaBKhongTraTaiLieuCuaA() {
    probe().assertAliasIsolated(aliasOf(tenantB()), tenantB(), tenantA(), documentIdsOf(tenantA()));
  }
}
