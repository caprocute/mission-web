package vn.viettel.okrkpi.search.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.search.application.EventSourcePort;
import vn.viettel.okrkpi.search.application.InboundEvent;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;

/**
 * Đọc lại topic sự kiện bằng một consumer riêng, gán thẳng phân vùng, không thuộc nhóm consumer và
 * không commit vị trí, nên không ảnh hưởng indexer trực tuyến.
 */
public final class KafkaReplaySource implements EventSourcePort {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaReplaySource.class);

  private final Supplier<KafkaConsumer<String, byte[]>> consumers;
  private final List<String> topics;
  private final EventDecoder decoder;

  public KafkaReplaySource(
      Supplier<KafkaConsumer<String, byte[]>> consumers,
      List<String> topics,
      EventDecoder decoder) {
    this.consumers = consumers;
    this.topics = topics;
    this.decoder = decoder;
  }

  @Override
  public Map<String, Long> endOffsets() {
    try (KafkaConsumer<String, byte[]> c = consumers.get()) {
      Map<String, Long> out = new HashMap<>();
      c.endOffsets(partitions(c)).forEach((tp, off) -> out.put(key(tp), off));
      return out;
    }
  }

  @Override
  public long replay(
      Map<String, Long> from, Map<String, Long> to, Consumer<List<InboundEvent>> sink) {
    try (KafkaConsumer<String, byte[]> c = consumers.get()) {
      Map<TopicPartition, Long> remaining = new HashMap<>();
      Map<TopicPartition, Long> start = new HashMap<>();
      for (TopicPartition tp : partitions(c)) {
        long end = to.getOrDefault(key(tp), 0L);
        long begin = from == null ? 0L : from.getOrDefault(key(tp), 0L);
        if (end > begin) {
          remaining.put(tp, end);
          start.put(tp, begin);
        }
      }
      if (remaining.isEmpty()) {
        return 0;
      }
      c.assign(remaining.keySet());
      Map<TopicPartition, Long> earliest = c.beginningOffsets(remaining.keySet());
      start.forEach((tp, off) -> c.seek(tp, Math.max(off, earliest.getOrDefault(tp, 0L))));
      long count = 0;
      while (!remaining.isEmpty()) {
        ConsumerRecords<String, byte[]> polled = c.poll(Duration.ofMillis(500));
        List<InboundEvent> batch = new ArrayList<>(polled.count());
        for (ConsumerRecord<String, byte[]> r : polled) {
          TopicPartition tp = new TopicPartition(r.topic(), r.partition());
          Long end = remaining.get(tp);
          if (end == null || r.offset() >= end) {
            continue;
          }
          try {
            batch.add(decoder.decode(batch.size(), r));
          } catch (PermanentInboxException e) {
            LOG.warn("Bỏ thông điệp không giải mã được khi phát lại {}@{}", tp, r.offset());
          }
          count++;
        }
        if (!batch.isEmpty()) {
          sink.accept(batch);
        }
        for (TopicPartition tp : new ArrayList<>(remaining.keySet())) {
          if (c.position(tp) >= remaining.get(tp)) {
            remaining.remove(tp);
            c.pause(List.of(tp));
          }
        }
      }
      return count;
    }
  }

  private List<TopicPartition> partitions(KafkaConsumer<String, byte[]> c) {
    List<TopicPartition> out = new ArrayList<>();
    for (String t : topics) {
      List<PartitionInfo> infos = c.partitionsFor(t);
      if (infos != null) {
        infos.forEach(p -> out.add(new TopicPartition(p.topic(), p.partition())));
      }
    }
    return out;
  }

  private static String key(TopicPartition tp) {
    return tp.topic() + "-" + tp.partition();
  }
}
