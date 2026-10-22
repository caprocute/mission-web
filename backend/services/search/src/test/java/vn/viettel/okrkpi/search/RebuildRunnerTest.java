package vn.viettel.okrkpi.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import vn.viettel.okrkpi.search.application.EventSourcePort;
import vn.viettel.okrkpi.search.application.IndexFailure;
import vn.viettel.okrkpi.search.application.RebuildScope;
import vn.viettel.okrkpi.search.application.RebuildService;
import vn.viettel.okrkpi.search.application.RebuildTarget;
import vn.viettel.okrkpi.search.application.SearchIndexPort;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.TenancyModel;
import vn.viettel.okrkpi.search.infrastructure.RebuildRunner;
import vn.viettel.okrkpi.search.infrastructure.SearchProperties;

/**
 * Lệnh dựng lại chạy một lần và thoát với mã 0 khi không lỗi; tenant tiếp nhận trễ được phát lại từ
 * đầu.
 */
class RebuildRunnerTest {

  @Test
  void chayDungLaiRoiThoat() {
    AtomicInteger replays = new AtomicInteger();
    String late = "0192f000-0000-7000-8000-0000000000c3";
    SearchIndexPort port =
        new SearchIndexPort() {
          @Override
          public void ensureTenant(String tenantId, TenancyModel model) {}

          @Override
          public List<IndexFailure> write(List<IndexCommand> commands) {
            return List.of();
          }

          @Override
          public RebuildTarget startRebuild(RebuildScope scope) {
            return new RebuildTarget(
                scope, "pool_task_v1", "pool_task_v2", new java.util.HashSet<>());
          }

          @Override
          public List<IndexFailure> write(RebuildTarget target, List<IndexCommand> commands) {
            return List.of();
          }

          @Override
          public Set<String> swap(RebuildTarget target) {
            return Set.of(late);
          }
        };
    EventSourcePort source =
        new EventSourcePort() {
          @Override
          public Map<String, Long> endOffsets() {
            return Map.of("t-0", 1L);
          }

          @Override
          public long replay(
              Map<String, Long> from,
              Map<String, Long> to,
              java.util.function.Consumer<List<vn.viettel.okrkpi.search.application.InboundEvent>>
                  sink) {
            replays.incrementAndGet();
            sink.accept(
                List.of(
                    new vn.viettel.okrkpi.search.application.InboundEvent(
                        0,
                        "t",
                        late,
                        Events.assigned(java.util.UUID.randomUUID(), "x", 1, false),
                        null)));
            return 1;
          }
        };
    SearchProperties props =
        new SearchProperties(null, null, null, null, null, null, null, 0, 0, 0, null, null, "pool");
    AtomicInteger code = new AtomicInteger(-1);
    try (GenericApplicationContext ctx = new GenericApplicationContext()) {
      ctx.refresh();
      new RebuildRunner(new RebuildService(port, source), props, ctx) {
        @Override
        protected void exit(int c) {
          code.set(c);
        }
      }.run(null);
    }
    assertThat(code.get()).isZero();
    assertThat(replays.get()).isEqualTo(3);
  }
}
