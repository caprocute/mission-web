package vn.viettel.okrkpi.shared.inbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * NEN-012 · Bộ đếm Micrometer cho inbox consumer theo từng {@code (consumer, topic, outcome)} và bộ
 * đếm tên riêng {@code okrkpi.inbox.<outcome>} gắn nhãn {@code consumer}, {@code topic}.
 */
public final class InboxMetrics {

  public static final String METER_MESSAGES = "okrkpi.inbox.messages";
  public static final String METER_PREFIX = "okrkpi.inbox.";

  private final MeterRegistry registry;

  public InboxMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  public void record(String consumer, String topic, InboxOutcome outcome) {
    Objects.requireNonNull(consumer, "consumer");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(outcome, "outcome");
    Counter.builder(METER_MESSAGES)
        .tag("consumer", consumer)
        .tag("topic", topic)
        .tag("outcome", outcome.tagValue())
        .register(registry)
        .increment();
    Counter.builder(METER_PREFIX + outcome.tagValue())
        .tag("consumer", consumer)
        .tag("topic", topic)
        .register(registry)
        .increment();
  }

  public double count(String consumer, String topic, InboxOutcome outcome) {
    Counter counter =
        registry
            .find(METER_MESSAGES)
            .tags("consumer", consumer, "topic", topic, "outcome", outcome.tagValue())
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}
