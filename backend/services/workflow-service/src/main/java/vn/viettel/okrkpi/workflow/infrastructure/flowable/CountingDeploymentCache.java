package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.common.engine.impl.persistence.deploy.DefaultDeploymentCache;

/**
 * Bộ đệm định nghĩa tiến trình có đếm trúng và trượt (TL-01 G2 "Tỷ lệ trượt bộ đệm định nghĩa").
 * Engine đọc lại bộ đệm sau khi nạp một định nghĩa bị trượt nên tỷ lệ đo được hơi thấp hơn thực tế
 * (suy luận).
 */
public class CountingDeploymentCache<T> extends DefaultDeploymentCache<T> {

  public static final String METRIC = "flowable.definition.cache.requests";

  private final Counter hits;
  private final Counter misses;

  public CountingDeploymentCache(int limit, MeterRegistry registry) {
    super(limit);
    hits = counter(registry, "hit");
    misses = counter(registry, "miss");
  }

  private static Counter counter(MeterRegistry registry, String result) {
    return Counter.builder(METRIC)
        .description("Số lần tra bộ đệm định nghĩa tiến trình theo kết quả")
        .tag("result", result)
        .register(registry);
  }

  @Override
  public T get(String id) {
    T value = super.get(id);
    (value == null ? misses : hits).increment();
    return value;
  }
}
