# `vt-tenant-test` — Bộ kiểm thử rò rỉ tenant tầng CSDL (NEN-034)

Thư viện đóng gói bốn lớp kiểm thử chống rò rỉ dữ liệu giữa các khách thuê ở tầng CSDL theo **ADR-07**, **ADR-22**, **TL-03 "Kiểm thử rò rỉ tenant" (L736–754)** và **TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS"**.

## 1. Bốn lớp bảo vệ trong CI

| Lớp | Cơ chế | Lớp kiểm thử / Công cụ |
|---|---|---|
| **Lớp 1 – Danh mục RLS** | Truy vấn `pg_class`/`pg_policy` (TL-03 L742–752) sau khi áp migration: mọi bảng và phân vùng con (`relispartition`) có `tenant_id` phải bật `relrowsecurity`, `relforcerowsecurity` và có `pg_policy`; phân vùng con bị thu hồi quyền trực tiếp của `svc_`, `ro_`, `cdc_`; bảng không có `tenant_id` phải khai báo trong `tenantlessTableExceptions()` kèm lý do. | `TenantIsolationCatalogTest` (`vt-db-migration` `test-jar`, kéo tự động qua `vt-tenant-test`) |
| **Lớp 2 – Hai tenant sinh tự động** | Với mỗi bảng có `tenant_id`, tự động tổng hợp dòng hợp lệ tối thiểu cho tenant A và B từ siêu dữ liệu cột, `NOT NULL`, `CHECK` và khoá ngoại trong cùng schema; kiểm dưới ngữ cảnh A: `SELECT/UPDATE/DELETE` dòng B = 0 dòng, `INSERT` mang `tenant_id` của B bị chặn (`42501`), không đặt ngữ cảnh thì `SELECT` rỗng. Kiểm thêm 16 phân vùng băm `_h00.._h15` ở chế độ `pool`. | `TenantIsolationCatalogTest` |
| **Lớp 3 – PgBouncer dùng lại kết nối** | Chạy PgBouncer 1.23 theo cấu hình tham chiếu `pgbouncer/pgbouncer.ini.template` (NEN-033) với `pool_mode = transaction` và `default_pool_size = 1`; thực thi ≥ 5.000 giao dịch xen kẽ của hai tenant qua `TenantJpaTransactionManager` và `JdbcClient` trên cùng một `pg_backend_pid`, chứng minh không có rò rỉ dòng hay `current_setting('app.tenant_id', true)`. | `PgBouncerReferenceConfig`, `TenantPgBouncerPoolIsolationIT` |
| **Lớp 4 – Quét mã tĩnh** | Luật Semgrep `backend/semgrep/tenant-context.yml` chặn `SET app.<biến>` không có `LOCAL`, `SET SESSION` và `set_config(..., false)` trên Java, Go, Python và SQL. | `semgrep --config backend/semgrep --error` và `SemgrepTenantRuleTest` |

## 2. Cách tích hợp vào mô-đun có migration

1. Khai báo phụ thuộc `vt-tenant-test` ở `scope=test` trong `pom.xml` của mô-đun:

```xml
<dependency>
  <groupId>vn.viettel.okrkpi</groupId>
  <artifactId>vt-tenant-test</artifactId>
  <version>${project.version}</version>
  <scope>test</scope>
</dependency>
```

2. Tạo một lớp `*IT.java` kế thừa `TenantIsolationCatalogTest`:

```java
package vn.viettel.okrkpi.okr;

import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

class OkrTenantIsolationIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    // Nếu schema nghiệp vụ có cột _enc tham chiếu keyring.dek(id), liệt kê "keyring" đứng trước.
    return List.of("keyring", "okr");
  }

  @Override
  protected Map<String, String> tenantlessTableExceptions() {
    // Chỉ khai báo khi schema thật sự có bảng danh mục toàn cục không chứa tenant_id.
    return Map.of();
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    // Chỉ cần cung cấp khi bảng có ràng buộc CHECK liên cột phức tạp không tự tổng hợp được.
    return Map.of();
  }
}
```

Khi mô-đun thêm bảng mới trong migration Flyway tiếp theo, `OkrTenantIsolationIT` tự động phát hiện và kiểm thử cả lớp 1, lớp 2 và bảo vệ phân vùng cho bảng mới mà không cần sửa mã kiểm thử.

## 3. Lưới chống rò rỉ ngoài tầng CSDL (NEN-035)

NEN-035 mở rộng lưới an toàn lên bốn tầng nằm ngoài PostgreSQL, theo ADR-07, ADR-14, ADR-15, ADR-18 và các mục "Bộ nhớ đệm Valkey", "Đánh chỉ mục tìm kiếm OpenSearch", "CDC phân tích sang ClickHouse" của TL-03.

| Tầng | Thành phần | Kiểm thử |
|---|---|---|
| API | Sinh từ hợp đồng mọi thao tác `get` trả danh sách (`items` + `nextCursor`) có `x-mission.owner` là `MOD-*`; mồi dòng hai tenant bằng bộ sinh của lớp 2; dưới ngữ cảnh A không phản hồi nào chứa mã của B, lấy theo mã của B trả 404 hoặc 403; thao tác chưa hiện thực được ghi nhận, không tính lỗi. | `mission-core` `ApiTenantLeakIT` |
| Bộ đệm Valkey | `TenantCacheKey` (khoá `t:<tenant>:<module>:<loại>:<mã>[:<phụ>]`, không tạo được khoá thiếu tenant) và `TenantCache`/`ValkeyTenantCache` trong `vt-platform-starter` gói `shared.cache`; luật ArchUnit `PlatformArchitectureRules.NO_DIRECT_VALKEY_CLIENT` cấm `RedisTemplate`, Lettuce và client Valkey khác ngoài gói `shared`. | `TenantCacheKeyTest`, `ValkeyTenantCacheIT` (Valkey 8 thật) |
| Tìm kiếm OpenSearch | `AbstractOpenSearchTenantLeakTest` và `OpenSearchTenantLeakProbe` (gói `search`): qua alias của A, `match_all`, `term` theo tenant B, `ids` của tài liệu B và gộp nhóm theo `tenant_id` không bao giờ lộ B. | `SampleOpenSearchTenantLeakIT` (chỉ mục mẫu `pool_task_v1`, ca âm alias thiếu bộ lọc) |
| Phân tích ClickHouse/Cube | `AbstractAnalyticsTenantLeakTest`, `AnalyticsTenantLeakProbe`, `TenantScopedQuery`, `ClickHouseHttpQuery` (gói `analytics`): dưới ngữ cảnh bảo mật của A mọi bảng chỉ trả dòng A kể cả khi lọc thẳng theo B; bảng phân tích không có cột C2 dạng rõ (danh mục suy từ cột `*_enc` của migration). | `SampleClickHouseTenantLeakIT` (row policy theo người dùng tenant, ca âm bảng thiếu row policy và bảng có `national_id`) |

DV-16 (SVC-16) và DV-15 (SVC-15) bắt buộc kế thừa hai lớp cơ sở `AbstractOpenSearchTenantLeakTest` và `AbstractAnalyticsTenantLeakTest` khi dựng chỉ mục và kho phân tích thật. Lưu ý cho SVC-16: bộ lọc của alias chỉ áp cho các API tìm kiếm (`_search`, `_count`); `GET <alias>/_doc/<id>` và `_mget` bỏ qua bộ lọc, nên không được dùng hai API này qua alias của tenant.

### Lớp 5 – tenant canh gác trên môi trường thật (để nhóm vận hành làm)

Mỗi cell pool có hai tenant "canh gác" với dữ liệu tổng hợp (sinh bằng bộ sinh dữ liệu thử của DL-08 ở hệ số nhỏ). Một job chạy mỗi giờ đăng nhập bằng tài khoản của tenant canh gác thứ nhất qua Keycloak thật, gọi các API danh sách chính (cùng danh sách thao tác mà `ApiTenantLeakIT` sinh từ hợp đồng) và tìm trong phản hồi mọi mã định danh của tenant canh gác thứ hai; chỉ cần một lần trùng là gửi cảnh báo mức nghiêm trọng tới SOC kèm thao tác, cell và mã vết. Job này không đọc CSDL, không dùng quyền đặc biệt và không được hiện thực trong NEN-035; nhóm VH/QA làm khi dựng môi trường.
