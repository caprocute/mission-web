package vn.viettel.okrkpi.platform.tenanttest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.platform.tenanttest.analytics.ClickHouseHttpQuery.Credentials;

/**
 * Kiểm thử tự kiểm của NEN-035 trên ClickHouse 24.8 thật: bảng phân tích mẫu {@code
 * analytics_sample.fact_task} có row policy theo {@code tenant_id} cho người dùng của từng tenant.
 * Kiểm thử âm: bảng thiếu row policy và bảng có cột C2 dạng rõ làm lớp cơ sở thất bại.
 */
class SampleClickHouseTenantLeakIT extends AbstractAnalyticsTenantLeakTest {

  private static final String A = "01927a00-0000-7000-8000-00000000010a";
  private static final String B = "01927a00-0000-7000-8000-00000000010b";
  private static final Credentials ADMIN = new Credentials("quan_tri", "chi-dung-cho-kiem-thu");

  @SuppressWarnings("resource")
  private static final GenericContainer<?> CLICKHOUSE =
      new GenericContainer<>(
              DockerImageName.parse("mirror.gcr.io/clickhouse/clickhouse-server:24.8-alpine"))
          .withEnv("CLICKHOUSE_USER", ADMIN.user())
          .withEnv("CLICKHOUSE_PASSWORD", ADMIN.password())
          .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
          .withExposedPorts(8123)
          .waitingFor(Wait.forHttp("/ping").forPort(8123));

  private static ClickHouseHttpQuery clickHouse;

  @BeforeAll
  static void seed() {
    CLICKHOUSE.start();
    clickHouse =
        new ClickHouseHttpQuery(
            URI.create("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123)),
            Map.of(
                A, new Credentials("tenant_a", "mat-khau-a"),
                B, new Credentials("tenant_b", "mat-khau-b")));
    for (String sql :
        List.of(
            "CREATE DATABASE analytics_sample",
            "CREATE TABLE analytics_sample.fact_task (tenant_id String, task_id String, status"
                + " String, unit_name String) ENGINE = MergeTree ORDER BY (tenant_id, task_id)",
            "CREATE TABLE analytics_sample.fact_leaky (tenant_id String, task_id String)"
                + " ENGINE = MergeTree ORDER BY (tenant_id, task_id)",
            "CREATE TABLE analytics_sample.dim_person_leaky (tenant_id String, person_id String,"
                + " national_id String) ENGINE = MergeTree ORDER BY (tenant_id, person_id)",
            "INSERT INTO analytics_sample.fact_task VALUES ('"
                + A
                + "','a-1','DA_GIAO','Phòng A'),('"
                + A
                + "','a-2','HOAN_THANH','Phòng A'),('"
                + B
                + "','b-1','DA_GIAO','Phòng B')",
            "INSERT INTO analytics_sample.fact_leaky VALUES ('"
                + A
                + "','a-1'),('"
                + B
                + "','b-1')",
            "CREATE USER tenant_a IDENTIFIED WITH plaintext_password BY 'mat-khau-a'",
            "CREATE USER tenant_b IDENTIFIED WITH plaintext_password BY 'mat-khau-b'",
            "GRANT SELECT ON analytics_sample.* TO tenant_a, tenant_b",
            "CREATE ROW POLICY pa ON analytics_sample.fact_task FOR SELECT USING tenant_id = '"
                + A
                + "' TO tenant_a",
            "CREATE ROW POLICY pb ON analytics_sample.fact_task FOR SELECT USING tenant_id = '"
                + B
                + "' TO tenant_b")) {
      clickHouse.execute(ADMIN, sql);
    }
  }

  @AfterAll
  static void stop() {
    CLICKHOUSE.stop();
  }

  private static Map<String, Collection<String>> columns(String database) {
    Map<String, Collection<String>> byTable = new TreeMap<>();
    for (Map<String, String> row :
        clickHouse.execute(
            ADMIN,
            "SELECT table, name FROM system.columns WHERE database = '"
                + database
                + "' FORMAT JSONEachRow")) {
      byTable.computeIfAbsent(row.get("table"), t -> new TreeSet<>()).add(row.get("name"));
    }
    return byTable;
  }

  @Override
  protected TenantScopedQuery scopedQuery() {
    return clickHouse;
  }

  @Override
  protected List<String> analyticsTables() {
    return List.of("analytics_sample.fact_task");
  }

  @Override
  protected Map<String, ? extends Collection<String>> analyticsColumns() {
    Map<String, Collection<String>> all = columns("analytics_sample");
    return Map.of("fact_task", all.get("fact_task"));
  }

  @Override
  protected Set<String> c2Columns() {
    return AnalyticsTenantLeakProbe.c2ColumnsFromMigrations(backendRoot());
  }

  @Override
  protected String tenantA() {
    return A;
  }

  @Override
  protected String tenantB() {
    return B;
  }

  private static Path backendRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !dir.resolve("mission-core").toFile().isDirectory()) {
      dir = dir.getParent();
    }
    assertThat(dir).as("không tìm thấy thư mục backend").isNotNull();
    return dir;
  }

  @Test
  void danhMucC2SuyTuMigration() {
    assertThat(c2Columns()).contains("national_id", "phone", "email", "birth_date");
  }

  @Test
  void bangThieuRowPolicyLamKiemThuThatBai() {
    assertThatThrownBy(
            () ->
                new AnalyticsTenantLeakProbe(clickHouse)
                    .assertTablesIsolated(List.of("analytics_sample.fact_leaky"), A, B))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("RÒ RỈ TENANT")
        .hasMessageContaining(B);
  }

  @Test
  void bangCoCotC2DangRoLamKiemThuThatBai() {
    assertThatThrownBy(
            () ->
                AnalyticsTenantLeakProbe.assertNoPlaintextC2Columns(
                    columns("analytics_sample"), c2Columns()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("dim_person_leaky.national_id");
  }
}
