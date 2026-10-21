package vn.viettel.okrkpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import vn.viettel.okrkpi.platform.db.migration.guard.SchemaVersionGuardAutoConfiguration;

/**
 * Điểm khởi chạy {@code SVC-05 mission-core} — khối nguyên khối theo mô-đun chứa 8 mô-đun nghiệp vụ
 * ({@code MOD-01..MOD-08}) và bản sao tổ chức ({@code org_replica}) (ADR-02, TL-01).
 *
 * <p>Loại trừ {@link SchemaVersionGuardAutoConfiguration} đơn-DataSource của {@code
 * vt-db-migration} vì {@code mission-core} dùng {@link
 * vn.viettel.okrkpi.shared.datasource.MissionCoreSchemaVersionGuard} để đối chiếu cả 9 schema trên
 * từng {@link javax.sql.DataSource} riêng. Đồng thời loại các gói auto-configuration của {@code
 * vt-platform-starter} khỏi quét thành phần trực tiếp để chúng được nạp đúng qua {@code
 * AutoConfiguration.imports}.
 */
@Modulithic(
    systemName = "SVC-05 mission-core",
    sharedModules = {"shared", "contract", "cel", "platform"})
@EnableAsync
@SpringBootApplication(exclude = SchemaVersionGuardAutoConfiguration.class)
@ComponentScan(
    basePackages = "vn.viettel.okrkpi",
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern =
                "vn\\.viettel\\.okrkpi\\.shared\\.(error|tenant|outbox|inbox|observability|resilience|audit|idempotency|paging|job|authz|crypto|etag)\\..*"))
public class MissionCoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(MissionCoreApplication.class, args);
  }
}
