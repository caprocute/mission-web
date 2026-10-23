package vn.viettel.okrkpi.oversight.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintAdjustment;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintAdjustmentNewValue;
import vn.viettel.okrkpi.contract.oversight.model.ComplaintAdjustmentOldValue;

/**
 * Giá trị {@code oneOf: [number, string]} của {@code ComplaintAdjustment}: lớp sinh từ hợp đồng
 * không mang giá trị, nên hai lớp con giữ giá trị vô hướng ({@link JsonValue}) và {@link #module()}
 * dạy Jackson đọc số hoặc chuỗi thành lớp con (dùng cho thân yêu cầu và phát lại idempotency).
 */
public final class ComplaintAdjustmentJson {

  private ComplaintAdjustmentJson() {}

  /** Giá trị mới. */
  public static final class NewValue extends ComplaintAdjustmentNewValue {
    private final Object value;

    public NewValue(Object value) {
      this.value = value;
    }

    @JsonValue
    public Object value() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof NewValue n && Objects.equals(value, n.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }

  /** Giá trị cũ. */
  public static final class OldValue extends ComplaintAdjustmentOldValue {
    private final Object value;

    public OldValue(Object value) {
      this.value = value;
    }

    @JsonValue
    public Object value() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof OldValue n && Objects.equals(value, n.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }

  /** Mô-đun Jackson đọc giá trị vô hướng của điều chỉnh. */
  public static SimpleModule module() {
    SimpleModule m = new SimpleModule("oversight-complaint-adjustment");
    m.addDeserializer(
        ComplaintAdjustmentNewValue.class,
        new JsonDeserializer<>() {
          @Override
          public ComplaintAdjustmentNewValue deserialize(JsonParser p, DeserializationContext c)
              throws IOException {
            return new NewValue(scalar(p.readValueAsTree()));
          }
        });
    m.addDeserializer(
        ComplaintAdjustmentOldValue.class,
        new JsonDeserializer<>() {
          @Override
          public ComplaintAdjustmentOldValue deserialize(JsonParser p, DeserializationContext c)
              throws IOException {
            return new OldValue(scalar(p.readValueAsTree()));
          }

          @Override
          public ComplaintAdjustmentOldValue getNullValue(DeserializationContext c) {
            return null;
          }
        });
    return m;
  }

  /** Số thành {@link BigDecimal}, chuỗi giữ nguyên, kiểu khác thành {@code null} (bị từ chối). */
  static Object scalar(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isNumber()) {
      return n.decimalValue();
    }
    return n.isTextual() ? n.textValue() : null;
  }

  static Object value(Object v) {
    if (v instanceof NewValue n) {
      return n.value();
    }
    if (v instanceof OldValue o) {
      return o.value();
    }
    return null;
  }

  /** Miền → hợp đồng. */
  static List<ComplaintAdjustment> toDto(
      List<vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment> in) {
    List<ComplaintAdjustment> out = new ArrayList<>();
    for (var a : in) {
      ComplaintAdjustment d =
          new ComplaintAdjustment()
              .targetKey(a.targetKey())
              .newValue(new NewValue(a.newValue()))
              .reason(a.reason());
      d.setOldValue(a.oldValue() == null ? null : new OldValue(a.oldValue()));
      out.add(d);
    }
    return out;
  }

  /** Hợp đồng → miền. */
  static List<vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment> toDomain(
      List<ComplaintAdjustment> in) {
    List<vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment> out = new ArrayList<>();
    if (in == null) {
      return out;
    }
    for (ComplaintAdjustment a : in) {
      if (a == null) {
        continue;
      }
      out.add(
          new vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment(
              a.getTargetKey() == null ? "" : a.getTargetKey(),
              value(a.getOldValue()),
              value(a.getNewValue()),
              a.getReason()));
    }
    return out;
  }
}
