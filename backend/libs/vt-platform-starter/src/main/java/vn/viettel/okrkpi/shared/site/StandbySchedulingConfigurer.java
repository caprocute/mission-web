package vn.viettel.okrkpi.shared.site;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * INF-28 · Ở TTDL chờ, thay bộ lập lịch của {@code @Scheduled} bằng {@link
 * StandbyAwareTaskScheduler}. Chạy sau mọi {@link SchedulingConfigurer} của service để bọc đúng bộ
 * lập lịch service đã chọn.
 */
public class StandbySchedulingConfigurer implements SchedulingConfigurer, Ordered {

  private final SiteProperties properties;
  private final ObjectProvider<TaskScheduler> schedulers;

  public StandbySchedulingConfigurer(
      SiteProperties properties, ObjectProvider<TaskScheduler> schedulers) {
    this.properties = properties;
    this.schedulers = schedulers;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    TaskScheduler current = registrar.getScheduler();
    if (current == null) {
      current = schedulers.getIfUnique();
    }
    if (current == null || current instanceof StandbyAwareTaskScheduler) {
      return;
    }
    registrar.setTaskScheduler(new StandbyAwareTaskScheduler(current, properties::standby));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
