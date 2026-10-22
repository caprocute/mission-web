package vn.viettel.okrkpi.platform.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * Đếm thông điệp theo dấu thời gian bằng Admin API ({@code ListOffsets} theo timestamp), không đọc
 * nội dung: với mỗi partition, số thông điệp trong {@code [from, to)} là hiệu offset đầu tiên có
 * dấu thời gian ≥ {@code to} và ≥ {@code from}; không có thông điệp nào sau mốc thì lấy offset
 * cuối. Topic chưa có trả 0. Thông điệp bị xoá do hết hạn giữ hoặc nén không còn đếm được, nên cửa
 * sổ phải nằm trong thời hạn giữ của topic.
 */
public final class KafkaTopicCounter implements TopicCounter {

  private final Admin admin;
  private final Duration timeout;

  public KafkaTopicCounter(Admin admin, Duration timeout) {
    this.admin = Objects.requireNonNull(admin, "admin");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public long count(String topic, Instant from, Instant to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    try {
      TopicDescription d;
      try {
        d =
            admin
                .describeTopics(List.of(topic))
                .allTopicNames()
                .get(ms(), TimeUnit.MILLISECONDS)
                .get(topic);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof UnknownTopicOrPartitionException) {
          return 0;
        }
        throw e;
      }
      List<TopicPartition> parts =
          d.partitions().stream().map(p -> new TopicPartition(topic, p.partition())).toList();
      Map<TopicPartition, Long> latest = offsets(parts, OffsetSpec.latest());
      Map<TopicPartition, Long> start =
          offsets(parts, OffsetSpec.forTimestamp(from.toEpochMilli()));
      Map<TopicPartition, Long> end = offsets(parts, OffsetSpec.forTimestamp(to.toEpochMilli()));
      long total = 0;
      for (TopicPartition tp : parts) {
        long last = latest.get(tp);
        long s = start.get(tp) < 0 ? last : start.get(tp);
        long e = end.get(tp) < 0 ? last : end.get(tp);
        total += Math.max(0, e - s);
      }
      return total;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi đếm topic " + topic, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Không đếm được topic " + topic, e);
    }
  }

  private Map<TopicPartition, Long> offsets(List<TopicPartition> parts, OffsetSpec spec)
      throws InterruptedException, ExecutionException, TimeoutException {
    Map<TopicPartition, OffsetSpec> req = new HashMap<>();
    parts.forEach(p -> req.put(p, spec));
    Map<TopicPartition, ListOffsetsResultInfo> res =
        admin.listOffsets(req).all().get(ms(), TimeUnit.MILLISECONDS);
    Map<TopicPartition, Long> out = new HashMap<>();
    res.forEach((tp, info) -> out.put(tp, info.offset()));
    return out;
  }

  private long ms() {
    return timeout.toMillis();
  }
}
