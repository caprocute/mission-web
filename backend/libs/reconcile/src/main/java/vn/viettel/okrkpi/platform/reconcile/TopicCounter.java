package vn.viettel.okrkpi.platform.reconcile;

import java.time.Instant;

/** Đếm thông điệp của một topic có dấu thời gian trong {@code [from, to)} (R7). */
@FunctionalInterface
public interface TopicCounter {

  long count(String topic, Instant from, Instant to);
}
