package vn.viettel.okrkpi.shared.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Kiểm thử đơn vị cho ngân sách pool kết nối, chuẩn hoá URL PgBouncer và định tuyến đọc/ghi ({@link
 * ReadWriteRoutingDataSource} + {@link RecentWriteTracker}) theo TL-01 L863–902.
 */
class ModuleDataSourceRoutingTest {

  @AfterEach
  void cleanupTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void defaultPoolBudgetAcrossEightModulesIs24AndWithinLimit35() {
    Map<ModuleName, Integer> sizes = new EnumMap<>(ModuleName.class);
    for (ModuleName module : ModuleName.values()) {
      sizes.put(module, module.defaultMaxPoolSize());
    }
    int sum = sizes.values().stream().mapToInt(Integer::intValue).sum();
    assertThat(ModuleName.values()).hasSize(8);
    assertThat(sum).isEqualTo(24).isLessThanOrEqualTo(ModuleName.MAX_TOTAL_POOL_PER_POD);
    PgBouncerJdbcUrlFinalizer.validateTotalPoolBudget(sizes);
  }

  @Test
  void rejectsPoolBudgetExceeding35ConnectionsPerPod() {
    Map<ModuleName, Integer> oversized = new EnumMap<>(ModuleName.class);
    Arrays.stream(ModuleName.values()).forEach(m -> oversized.put(m, 5)); // 8 * 5 = 40 > 35
    assertThatThrownBy(() -> PgBouncerJdbcUrlFinalizer.validateTotalPoolBudget(oversized))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vượt trần 35");
  }

  @Test
  void ensuresPrepareThresholdZeroAndApplicationNameOnJdbcUrls() {
    String url1 =
        PgBouncerJdbcUrlFinalizer.ensurePgBouncerParams(
            "jdbc:postgresql://127.0.0.1:35432/mission?currentSchema=task", "mission-core-task");
    assertThat(url1)
        .contains("currentSchema=task")
        .contains("prepareThreshold=0")
        .contains("ApplicationName=mission-core-task");

    String url2 =
        PgBouncerJdbcUrlFinalizer.ensurePgBouncerParams(
            "jdbc:postgresql://127.0.0.1:35432/mission?prepareThreshold=5&ApplicationName=wrong",
            "mission-core-okr");
    assertThat(url2)
        .contains("prepareThreshold=0")
        .doesNotContain("prepareThreshold=5")
        .contains("ApplicationName=mission-core-okr");
  }

  @Test
  void routesWriteToRwAndReadOnlyToRoUnlessRecentWriteOrReplicaLagHigh() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T08:00:00Z"));
    Clock mutableClock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };

    RecentWriteTracker tracker = new RecentWriteTracker(mutableClock, Duration.ofSeconds(2));
    tracker.setExplicitActorKey("user-123");

    DataSource rwDs = dummyDataSource("rw");
    DataSource roDs = dummyDataSource("ro");
    ReadWriteRoutingDataSource routing =
        new ReadWriteRoutingDataSource(ModuleName.TASK, rwDs, roDs, true, 1_000L, tracker);

    // 1. Giao dịch ghi (readOnly = false) -> luôn đi RW
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RW);

    // 2. Giao dịch chỉ đọc (readOnly = true), chưa có ghi gần đây, lag = 0 -> đi RO
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RO);

    // 3. Sau khi ghi, trong vòng 2 giây -> giao dịch readOnly = true được ghim về RW
    tracker.recordWrite();
    now.set(now.get().plusMillis(1_500));
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RW);

    // 4. Sau 2 giây kể từ lúc ghi -> giao dịch readOnly = true quay lại RO
    now.set(now.get().plusMillis(600)); // tổng 2.100 ms >= 2.000 ms
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RO);

    // 5. Khi độ trễ nhân bản > 1.000 ms -> giao dịch readOnly = true tự động lùi về RW
    tracker.updateReplicaLagMs(1_500L);
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RW);

    // 6. Khi độ trễ nhân bản hạ xuống <= 1.000 ms -> đọc từ RO trở lại
    tracker.updateReplicaLagMs(400L);
    assertThat(routing.resolveRoute()).isEqualTo(DataSourceRoute.RO);
  }

  private static DataSource dummyDataSource(String name) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("toString".equals(method.getName())) {
                return name;
              }
              if ("hashCode".equals(method.getName())) {
                return name.hashCode();
              }
              if ("equals".equals(method.getName())) {
                return proxy == args[0];
              }
              return null;
            });
  }
}
