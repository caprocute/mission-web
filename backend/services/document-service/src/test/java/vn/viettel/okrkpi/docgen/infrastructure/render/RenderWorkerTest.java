package vn.viettel.okrkpi.docgen.infrastructure.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** Worker sinh song song có giới hạn (DV-11-04). */
class RenderWorkerTest {

  @Test
  void everyPendingJobIsProcessedInItsTenantWithOrWithoutPool() {
    for (int concurrency : new int[] {1, 3}) {
      UUID tenant = UUID.randomUUID();
      List<PendingJobScanner.Pending> batch =
          IntStream.range(0, 10)
              .mapToObj(i -> new PendingJobScanner.Pending(UUID.randomUUID(), tenant))
              .toList();
      PendingJobScanner scanner = mock(PendingJobScanner.class);
      when(scanner.scan(anyInt(), any())).thenReturn(batch);
      RenderService service = mock(RenderService.class);
      Set<UUID> seen = ConcurrentHashMap.newKeySet();
      when(service.processOnce(any()))
          .thenAnswer(
              inv -> {
                assertThat(TenantContext.require().value()).isEqualTo(tenant);
                UUID id = inv.getArgument(0);
                seen.add(id);
                if (id.equals(batch.get(3).id())) {
                  throw new IllegalStateException("lỗi giả lập");
                }
                return null;
              });
      try (RenderWorker worker =
          new RenderWorker(scanner, service, 4, Duration.ofMinutes(5), concurrency)) {
        worker.poll();
      }
      assertThat(seen).hasSize(10);
    }
  }
}
