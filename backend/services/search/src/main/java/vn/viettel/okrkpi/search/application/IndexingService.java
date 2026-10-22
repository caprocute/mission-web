package vn.viettel.okrkpi.search.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.TenancyModel;

/**
 * Xử lý một lô sự kiện của indexer: bảo đảm bí danh tenant tồn tại, dựng thao tác, ghi một lệnh
 * {@code _bulk}, đo độ trễ từ lúc sự kiện phát ({@code occurredat}) tới lúc ghi xong. Chỉ mục làm
 * mới mỗi giây, nên độ trễ tới lúc tìm được bằng độ trễ đo ở đây cộng tối đa một chu kỳ làm mới
 * (mục tiêu p99 ≤ 10 giây, TL-03 bảng 10.6).
 */
public class IndexingService {

  public static final String LAG_METRIC = "mission.search.index.lag";

  private final SearchIndexPort index;
  private final TenancyModel defaultModel;
  private final Clock clock;
  private final Timer lag;
  private final MeterRegistry meters;

  public IndexingService(
      SearchIndexPort index, TenancyModel defaultModel, MeterRegistry meters, Clock clock) {
    this.index = index;
    this.defaultModel = defaultModel;
    this.clock = clock;
    this.meters = meters;
    this.lag =
        Timer.builder(LAG_METRIC)
            .description("Độ trễ từ lúc phát sự kiện tới lúc ghi xong vào chỉ mục")
            .publishPercentiles(0.5, 0.99)
            .publishPercentileHistogram()
            .register(meters);
  }

  public List<IndexFailure> index(List<InboundEvent> events) {
    List<IndexCommand> commands = new ArrayList<>(events.size());
    Set<String> tenants = new HashSet<>();
    for (InboundEvent e : events) {
      Optional<IndexCommand> c = EventMapper.map(e);
      if (c.isPresent()) {
        commands.add(c.get());
        tenants.add(c.get().tenantId());
      }
    }
    if (commands.isEmpty()) {
      return List.of();
    }
    // Sự kiện của tenant chưa nhận sys.tenant.provisioned.v1 dùng mô hình mặc định của cell.
    tenants.forEach(t -> index.ensureTenant(t, defaultModel));
    List<IndexFailure> failures = index.write(commands);
    Set<Integer> failed = new HashSet<>();
    failures.forEach(f -> failed.add(f.seq()));
    var now = clock.instant();
    for (IndexCommand c : commands) {
      if (c.occurredAt() != null && !failed.contains(c.seq())) {
        lag.record(Duration.between(c.occurredAt(), now).abs());
      }
    }
    meters.counter("mission.search.index.documents").increment(commands.size() - failures.size());
    return failures;
  }

  public void provision(String tenantId, TenancyModel model) {
    index.ensureTenant(tenantId, model);
  }
}
