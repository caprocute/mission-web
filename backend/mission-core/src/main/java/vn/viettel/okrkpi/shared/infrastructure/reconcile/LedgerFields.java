package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Đọc trường của tải trọng Avro generic (không chuyển kiểu logic) và ghi trường lệch cho các {@link
 * LedgerSource}: uuid là chuỗi, decimal là byte, date là số ngày, timestamp-micros là số micro
 * giây.
 */
public final class LedgerFields {

  private static final Conversions.DecimalConversion DECIMAL = new Conversions.DecimalConversion();

  private final String topic;
  private final GenericRecord payload;
  private final List<String> out;

  private LedgerFields(String topic, GenericRecord payload, List<String> out) {
    this.topic = topic;
    this.payload = payload;
    this.out = out;
  }

  /**
   * Bộ so trường của một sự kiện; trường lệch ghi vào {@code out} dạng {@code <topic>.<trường>}.
   */
  public static LedgerFields of(LedgerSource.SourceEvent e, List<String> out) {
    return new LedgerFields(e.topic(), e.payload(), out);
  }

  public GenericRecord payload() {
    return payload;
  }

  /** Ghi trường lệch khi {@code ok} sai. */
  public void check(String field, boolean ok) {
    if (!ok) {
      out.add(topic + "." + field);
    }
  }

  /** Dòng nguồn của sự kiện không còn: ghi lệch ở trường khoá. */
  public void missing(String keyField) {
    out.add(topic + "." + keyField);
  }

  public UUID uuid(String field) {
    Object v = payload.get(field);
    return v == null ? null : UUID.fromString(v.toString());
  }

  public String string(String field) {
    Object v = payload.get(field);
    return v == null ? null : v.toString();
  }

  public Integer integer(String field) {
    Object v = payload.get(field);
    return v == null ? null : ((Number) v).intValue();
  }

  public Boolean bool(String field) {
    return (Boolean) payload.get(field);
  }

  public LocalDate date(String field) {
    Integer v = integer(field);
    return v == null ? null : LocalDate.ofEpochDay(v);
  }

  public Instant micros(String field) {
    Object v = payload.get(field);
    return v == null ? null : Instant.EPOCH.plus(((Number) v).longValue(), ChronoUnit.MICROS);
  }

  /** Giá trị decimal (bytes) của trường, kể cả trường union với null. */
  public BigDecimal decimal(String field) {
    Object v = payload.get(field);
    if (v == null) {
      return null;
    }
    Schema s = payload.getSchema().getField(field).schema();
    if (s.getType() == Schema.Type.UNION) {
      s =
          s.getTypes().stream()
              .filter(t -> t.getType() == Schema.Type.BYTES)
              .findFirst()
              .orElseThrow();
    }
    return DECIMAL.fromBytes(
        ((ByteBuffer) v).duplicate(), s, (LogicalTypes.Decimal) s.getLogicalType());
  }

  /** Byte của trường kiểu bytes. */
  public byte[] bytes(String field) {
    Object v = payload.get(field);
    if (v == null) {
      return null;
    }
    ByteBuffer b = ((ByteBuffer) v).duplicate();
    byte[] out = new byte[b.remaining()];
    b.get(out);
    return out;
  }

  /** Tập uuid của trường mảng, đã sắp để so không theo thứ tự. */
  public TreeSet<UUID> uuids(String field) {
    TreeSet<UUID> s = new TreeSet<>();
    Object v = payload.get(field);
    if (v instanceof Collection<?> c) {
      c.forEach(x -> s.add(UUID.fromString(x.toString())));
    }
    return s;
  }

  // ------------------------------------------------------------- so với cột

  /** So uuid của tải trọng với cột (null bằng null). */
  public void sameUuid(String field, Object column) {
    check(field, Objects.equals(uuid(field), column));
  }

  public void sameString(String field, Object column) {
    check(field, Objects.equals(string(field), column == null ? null : column.toString()));
  }

  public void sameInt(String field, Object column) {
    Integer v = integer(field);
    check(
        field,
        v == null ? column == null : column instanceof Number n && n.intValue() == v.intValue());
  }

  public void sameBool(String field, Object column) {
    check(field, Objects.equals(bool(field), column));
  }

  /** So decimal theo giá trị (bỏ qua số chữ số thập phân). */
  public void sameDecimal(String field, Object column) {
    BigDecimal v = decimal(field);
    check(
        field, v == null ? column == null : column instanceof BigDecimal c && v.compareTo(c) == 0);
  }

  public void sameDate(String field, Object column) {
    LocalDate v = date(field);
    check(
        field,
        v == null
            ? column == null
            : column instanceof java.sql.Date d && v.equals(d.toLocalDate()));
  }

  /** So thời điểm tới micro giây với cột {@code timestamptz}. */
  public void sameInstant(String field, Object column) {
    Instant v = micros(field);
    check(
        field,
        v == null
            ? column == null
            : column instanceof Timestamp t
                && v.equals(t.toInstant().truncatedTo(ChronoUnit.MICROS)));
  }

  /** So tập uuid của trường mảng với danh sách cột. */
  public void sameUuidSet(String field, Collection<UUID> column) {
    check(field, uuids(field).equals(new TreeSet<>(column)));
  }

  /** Các trường lệch đã ghi, giữ thứ tự. */
  public static List<String> distinct(List<String> out) {
    return new ArrayList<>(new java.util.LinkedHashSet<>(out));
  }
}
