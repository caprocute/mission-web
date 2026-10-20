package vn.viettel.okrkpi.shared.inbox;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.util.ReflectionUtils;

/**
 * NEN-012 · Mô tả một phương thức {@link InboxListener} đã đăng ký, hỗ trợ truyền tham số dạng
 * {@code (T payload)}, {@code (InboxEnvelope<T> envelope)}, hoặc {@code (T payload,
 * InboxEnvelope<T> envelope)}.
 */
public final class InboxEndpoint {

  static final int CONSUMER_MAX_LENGTH = 100;
  static final int AGGREGATE_TYPE_MAX_LENGTH = 64;

  private final String topic;
  private final String consumer;
  private final String aggregateType;
  private final Object bean;
  private final Method method;
  private final InvocationStyle style;

  private enum InvocationStyle {
    PAYLOAD_ONLY,
    ENVELOPE_ONLY,
    PAYLOAD_AND_ENVELOPE,
    ENVELOPE_AND_PAYLOAD
  }

  public InboxEndpoint(
      String topic, String consumer, String aggregateType, Object bean, Method method) {
    this.topic = requireNonBlank(topic, "topic", 128);
    this.consumer = requireNonBlank(consumer, "consumer", CONSUMER_MAX_LENGTH);
    this.aggregateType = normalizeAggregateType(aggregateType);
    this.bean = Objects.requireNonNull(bean, "bean");
    this.method = Objects.requireNonNull(method, "method");
    this.style = resolveStyle(method);
    ReflectionUtils.makeAccessible(this.method);
  }

  public static InboxEndpoint fromMethod(Object bean, Method method, InboxListener annotation) {
    Objects.requireNonNull(annotation, "annotation");
    return new InboxEndpoint(
        annotation.topic(), annotation.consumer(), annotation.aggregateType(), bean, method);
  }

  public String topic() {
    return topic;
  }

  public String consumer() {
    return consumer;
  }

  public String aggregateType() {
    return aggregateType;
  }

  public boolean hasAggregateType() {
    return !aggregateType.isEmpty();
  }

  public Object bean() {
    return bean;
  }

  public Method method() {
    return method;
  }

  public void invoke(InboxEnvelope<? extends SpecificRecord> envelope) {
    Objects.requireNonNull(envelope, "envelope");
    Object[] args =
        switch (style) {
          case PAYLOAD_ONLY -> new Object[] {envelope.payload()};
          case ENVELOPE_ONLY -> new Object[] {envelope};
          case PAYLOAD_AND_ENVELOPE -> new Object[] {envelope.payload(), envelope};
          case ENVELOPE_AND_PAYLOAD -> new Object[] {envelope, envelope.payload()};
        };
    try {
      method.invoke(bean, args);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Không gọi được phương thức @InboxListener " + method, e);
    } catch (InvocationTargetException e) {
      Throwable target = e.getTargetException();
      if (target instanceof RuntimeException re) {
        throw re;
      }
      if (target instanceof Error err) {
        throw err;
      }
      throw new RuntimeException(target);
    }
  }

  private static InvocationStyle resolveStyle(Method method) {
    Class<?>[] params = method.getParameterTypes();
    if (params.length == 1) {
      if (InboxEnvelope.class.isAssignableFrom(params[0])) {
        return InvocationStyle.ENVELOPE_ONLY;
      }
      if (SpecificRecord.class.isAssignableFrom(params[0])) {
        return InvocationStyle.PAYLOAD_ONLY;
      }
    } else if (params.length == 2) {
      if (SpecificRecord.class.isAssignableFrom(params[0])
          && InboxEnvelope.class.isAssignableFrom(params[1])) {
        return InvocationStyle.PAYLOAD_AND_ENVELOPE;
      }
      if (InboxEnvelope.class.isAssignableFrom(params[0])
          && SpecificRecord.class.isAssignableFrom(params[1])) {
        return InvocationStyle.ENVELOPE_AND_PAYLOAD;
      }
    }
    throw new IllegalArgumentException(
        "Phương thức @InboxListener phải nhận (SpecificRecord), (InboxEnvelope) hoặc"
            + " (SpecificRecord, InboxEnvelope): "
            + method);
  }

  private static String requireNonBlank(String value, String field, int maxLen) {
    Objects.requireNonNull(value, field);
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > maxLen) {
      throw new IllegalArgumentException(field + " rỗng hoặc vượt quá " + maxLen + " ký tự");
    }
    return trimmed;
  }

  private static String normalizeAggregateType(String aggregateType) {
    if (aggregateType == null || aggregateType.isBlank()) {
      return "";
    }
    String trimmed = aggregateType.trim();
    if (trimmed.length() > AGGREGATE_TYPE_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "aggregateType vượt quá " + AGGREGATE_TYPE_MAX_LENGTH + " ký tự");
    }
    return trimmed;
  }
}
