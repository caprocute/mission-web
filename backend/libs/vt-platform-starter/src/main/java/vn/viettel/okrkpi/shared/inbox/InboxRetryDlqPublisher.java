package vn.viettel.okrkpi.shared.inbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaOperations;

/**
 * NEN-012 · Phát thông điệp lỗi sang topic thử lại ({@code <topic>.retry.<consumer>}) hoặc thư chết
 * ({@code <topic>.dlq.<consumer>}) theo quy ước TL-03 "Inbox idempotent".
 */
public class InboxRetryDlqPublisher {

  private static final long SEND_TIMEOUT_SECONDS = 30L;

  private final KafkaOperations<String, byte[]> kafka;

  public InboxRetryDlqPublisher(KafkaOperations<String, byte[]> kafka) {
    this.kafka = Objects.requireNonNull(kafka, "kafka");
  }

  /** Tên topic thử lại của một consumer: {@code <sourceTopic>.retry.<consumer>}. */
  public static String retryTopic(String sourceTopic, String consumer) {
    return sourceTopic + ".retry." + consumer;
  }

  /** Tên topic thư chết của một consumer: {@code <sourceTopic>.dlq.<consumer>}. */
  public static String dlqTopic(String sourceTopic, String consumer) {
    return sourceTopic + ".dlq." + consumer;
  }

  /**
   * Chuyển thông điệp gặp lỗi tạm thời (sau khi hết số lần thử tại chỗ) sang {@code
   * <sourceTopic>.retry.<consumer>}.
   */
  public void publishToRetry(
      ConsumerRecord<String, byte[]> original,
      String sourceTopic,
      String consumer,
      int nextTier,
      Duration delay,
      Instant notBefore,
      Throwable error) {
    Objects.requireNonNull(original, "original");
    String targetTopic = retryTopic(sourceTopic, consumer);
    Headers headers = copyHeaders(original.headers());
    putUtf8(headers, InboxHeaders.RETRY_TIER, Integer.toString(nextTier));
    putUtf8(headers, InboxHeaders.RETRY_DELAY_MS, Long.toString(delay.toMillis()));
    putUtf8(headers, InboxHeaders.RETRY_NOT_BEFORE, notBefore.toString());
    putUtf8(headers, InboxHeaders.RETRY_LAST_ERROR, safeMessage(error));
    sendSync(new ProducerRecord<>(targetTopic, null, original.key(), original.value(), headers));
  }

  /**
   * Chuyển thông điệp gặp lỗi vĩnh viễn (hoặc quá số bậc thử lại) sang {@code
   * <sourceTopic>.dlq.<consumer>}.
   */
  public void publishToDlq(
      ConsumerRecord<String, byte[]> original,
      String sourceTopic,
      String consumer,
      String errorCode,
      Throwable error,
      Instant failedAt) {
    Objects.requireNonNull(original, "original");
    String targetTopic = dlqTopic(sourceTopic, consumer);
    Headers headers = copyHeaders(original.headers());
    putUtf8(headers, InboxHeaders.DLQ_REASON, safeMessage(error));
    putUtf8(
        headers,
        InboxHeaders.DLQ_ERROR_CODE,
        errorCode == null || errorCode.isBlank()
            ? PermanentInboxException.DEFAULT_ERROR_CODE
            : errorCode);
    putUtf8(
        headers,
        InboxHeaders.DLQ_EXCEPTION_CLASS,
        error == null ? "UnknownException" : error.getClass().getName());
    putUtf8(headers, InboxHeaders.DLQ_SOURCE_TOPIC, sourceTopic);
    putUtf8(headers, InboxHeaders.DLQ_CONSUMER, consumer);
    putUtf8(headers, InboxHeaders.DLQ_FAILED_AT, failedAt.toString());
    sendSync(new ProducerRecord<>(targetTopic, null, original.key(), original.value(), headers));
  }

  private void sendSync(ProducerRecord<String, byte[]> record) {
    try {
      kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi gửi thông điệp sang topic " + record.topic(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Không gửi được thông điệp sang topic " + record.topic(), e);
    }
  }

  private static Headers copyHeaders(Headers source) {
    RecordHeaders copy = new RecordHeaders();
    if (source != null) {
      for (Header h : source) {
        copy.add(h.key(), h.value());
      }
    }
    return copy;
  }

  private static void putUtf8(Headers headers, String key, String value) {
    headers.remove(key);
    if (value != null) {
      headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String safeMessage(Throwable error) {
    if (error == null) {
      return "unknown";
    }
    String msg = error.getMessage();
    return msg == null || msg.isBlank() ? error.getClass().getSimpleName() : msg;
  }
}
