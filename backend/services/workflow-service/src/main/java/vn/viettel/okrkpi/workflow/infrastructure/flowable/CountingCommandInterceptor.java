package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.interceptor.AbstractCommandInterceptor;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandConfig;
import org.flowable.common.engine.impl.interceptor.CommandExecutor;

/**
 * Đếm giao dịch của engine (mỗi lệnh Flowable là một giao dịch) và số lần {@link
 * FlowableOptimisticLockingException}, cho chỉ số tỷ lệ xung đột cập nhật đồng thời của TL-01 G2.
 */
public class CountingCommandInterceptor extends AbstractCommandInterceptor {

  public static final String COMMANDS = "flowable.commands";
  public static final String OPTIMISTIC_LOCKING = "flowable.optimistic.locking.failures";

  private final Counter commands;
  private final Counter conflicts;

  public CountingCommandInterceptor(MeterRegistry registry) {
    commands =
        Counter.builder(COMMANDS).description("Số lệnh (giao dịch) của engine").register(registry);
    conflicts =
        Counter.builder(OPTIMISTIC_LOCKING)
            .description("Số lệnh lỗi FlowableOptimisticLockingException")
            .register(registry);
  }

  @Override
  public <T> T execute(CommandConfig config, Command<T> command, CommandExecutor executor) {
    commands.increment();
    try {
      return next.execute(config, command, executor);
    } catch (FlowableOptimisticLockingException e) {
      conflicts.increment();
      throw e;
    }
  }
}
