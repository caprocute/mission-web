package vn.viettel.okrkpi.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.search.domain.IndexCommand;

/**
 * Dựng lại chỉ mục khi đổi mapping (TL-03): tạo {@code v<n+1>}, phát lại sự kiện từ đầu kỳ lưu giữ
 * của topic tới vị trí cuối {@code E1} ghi nhận lúc bắt đầu, hoán bí danh nguyên tử, rồi đọc bù
 * khoảng {@code [E1, E2)} với {@code E2} là vị trí cuối sau khi hoán. Sự kiện indexer trực tuyến
 * ghi vào chỉ mục cũ trong lúc nạp đều nằm trước {@code E2}, nên được đọc bù; ghi lặp an toàn nhờ
 * kiểm phiên bản. Chỉ mục cũ được giữ lại để truy vấn đang chạy trên nó không lỗi; xoá chỉ mục cũ
 * là bước vận hành riêng.
 *
 * <p>Nguồn phát lại là topic Kafka (suy luận: TL-03 ghi "lệnh phát lại sự kiện của module sở hữu";
 * khi kỳ lưu giữ topic ngắn hơn vòng đời dữ liệu, module sở hữu phát lại sự kiện vào topic trước
 * khi chạy lệnh này).
 */
public class RebuildService {

  private static final Logger LOG = LoggerFactory.getLogger(RebuildService.class);

  private final SearchIndexPort index;
  private final EventSourcePort source;

  public RebuildService(SearchIndexPort index, EventSourcePort source) {
    this.index = index;
    this.source = source;
  }

  public record Report(
      String oldIndex, String newIndex, long replayed, long caughtUp, long failures) {}

  public Report rebuild(RebuildScope scope) {
    RebuildTarget target = index.startRebuild(scope);
    LOG.info(
        "Dựng lại chỉ mục {} sang {} cho {} tenant",
        target.oldIndex(),
        target.newIndex(),
        target.tenants().size());
    AtomicLong failures = new AtomicLong();
    Map<String, Long> e1 = source.endOffsets();
    long replayed = source.replay(null, e1, batch -> failures.addAndGet(load(target, batch)));
    java.util.Set<String> late = index.swap(target);
    Map<String, Long> e2 = source.endOffsets();
    long caughtUp = source.replay(e1, e2, batch -> failures.addAndGet(load(target, batch)));
    if (!late.isEmpty()) {
      // Tenant pool tiếp nhận trong lúc nạp: phát lại riêng phần của chúng từ đầu (hiếm, chỉ khi
      // tiếp nhận trùng lúc).
      RebuildTarget only = new RebuildTarget(scope, target.oldIndex(), target.newIndex(), late);
      caughtUp += source.replay(null, e1, batch -> failures.addAndGet(load(only, batch)));
    }
    LOG.info(
        "Đã hoán bí danh sang {}: phát lại {} sự kiện, đọc bù {}, lỗi {}",
        target.newIndex(),
        replayed,
        caughtUp,
        failures.get());
    return new Report(target.oldIndex(), target.newIndex(), replayed, caughtUp, failures.get());
  }

  private long load(RebuildTarget target, List<InboundEvent> batch) {
    List<IndexCommand> commands = new ArrayList<>();
    for (InboundEvent e : batch) {
      Optional<IndexCommand> c = EventMapper.map(e);
      if (c.isPresent() && target.tenants().contains(c.get().tenantId())) {
        commands.add(c.get());
      }
    }
    return commands.isEmpty() ? 0 : index.write(target, commands).size();
  }
}
