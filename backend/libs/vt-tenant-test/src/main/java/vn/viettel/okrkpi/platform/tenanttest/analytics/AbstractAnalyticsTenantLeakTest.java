package vn.viettel.okrkpi.platform.tenanttest.analytics;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * NEN-035: lớp cơ sở mà kiểm thử phân tích của DV-15 bắt buộc kế thừa. Lớp con nạp dữ liệu mồi của
 * hai tenant vào các bảng phân tích, rồi khai cách chạy truy vấn dưới ngữ cảnh bảo mật tenant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractAnalyticsTenantLeakTest {

  protected abstract TenantScopedQuery scopedQuery();

  /** Bảng (hoặc cube/view) phân tích có cột {@code tenant_id} cần kiểm. */
  protected abstract List<String> analyticsTables();

  /** Cột của từng bảng phân tích (đọc từ {@code system.columns} hoặc mô hình Cube). */
  protected abstract Map<String, ? extends Collection<String>> analyticsColumns();

  /** Tên trường C2 bị cấm ở dạng rõ; mặc định suy từ cột {@code *_enc} của migration. */
  protected abstract Set<String> c2Columns();

  protected abstract String tenantA();

  protected abstract String tenantB();

  @Test
  void nguCanhACho0DongCuaB() {
    new AnalyticsTenantLeakProbe(scopedQuery())
        .assertTablesIsolated(analyticsTables(), tenantA(), tenantB());
  }

  @Test
  void nguCanhBCho0DongCuaA() {
    new AnalyticsTenantLeakProbe(scopedQuery())
        .assertTablesIsolated(analyticsTables(), tenantB(), tenantA());
  }

  @Test
  void khongCoCotC2DangRo() {
    AnalyticsTenantLeakProbe.assertNoPlaintextC2Columns(analyticsColumns(), c2Columns());
  }
}
