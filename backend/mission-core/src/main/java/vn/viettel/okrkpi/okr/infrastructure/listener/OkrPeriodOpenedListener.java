package vn.viettel.okrkpi.okr.infrastructure.listener;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.config.api.PeriodOpenedEvent;
import vn.viettel.okrkpi.shared.kernel.PeriodId;

/**
 * Trình lắng nghe sự kiện nội bộ của {@code MOD-01 · OKR Engine} tiếp nhận {@link
 * PeriodOpenedEvent} từ {@code MOD-08 (config)} thông qua Spring Modulith Event Publication
 * Registry ({@code modulith.event_publication}).
 */
@Component
public class OkrPeriodOpenedListener {

  private final List<PeriodOpenedEvent> handledEvents = new CopyOnWriteArrayList<>();
  private final AtomicBoolean failNextExecution = new AtomicBoolean(false);

  @ApplicationModuleListener
  public void onPeriodOpened(PeriodOpenedEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    if (failNextExecution.get()) {
      throw new IllegalStateException(
          "Simulated failure in OkrPeriodOpenedListener for period " + event.periodId().value());
    }
    handledEvents.add(event);
  }

  public List<PeriodOpenedEvent> handledEvents() {
    return List.copyOf(handledEvents);
  }

  public boolean hasHandled(PeriodId periodId) {
    return handledEvents.stream().anyMatch(e -> e.periodId().equals(periodId));
  }

  public void setFailNextExecution(boolean fail) {
    failNextExecution.set(fail);
  }

  public void reset() {
    failNextExecution.set(false);
    handledEvents.clear();
  }
}
