package vn.viettel.okrkpi.task.infrastructure.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/**
 * Nguồn R2 của MOD-02 (thí điểm DL-12): EVT-04 {@code task.task.assigned.v1} so với {@code
 * task.task}, EVT-05 {@code task.task.progressed.v1} so với {@code task.task_progress}.
 *
 * <ul>
 *   <li>EVT-05: dòng tiến độ chỉ ghi thêm nên mọi trường của tải trọng phải khớp dòng có {@code id
 *       = progressId} (người báo là {@code created_by}, tỷ lệ là {@code progress_pct} × 100 làm
 *       tròn như lúc phát).
 *   <li>EVT-04: {@code plan_item_id} không đổi sau khi giao nên luôn so (suy luận); các trường còn
 *       lại (tiêu đề khi không mật, then chốt, mật, hạn) chỉ so khi {@code row_version} hiện hành
 *       bằng {@code aggregateVersion} của sự kiện, vì giao lại, gia hạn hợp lệ đổi chúng. Người chủ
 *       trì và phối hợp chưa so (bảng {@code task_assignee} đổi theo phân công lại).
 * </ul>
 */
public class TaskLedgerSource implements LedgerSource {

  static final String ASSIGNED = EventTopics.EVT_04_NHIEM_VU_DA_GIAO;
  static final String PROGRESSED = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT;

  private static final Conversions.DecimalConversion DECIMAL = new Conversions.DecimalConversion();

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public TaskLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "task";
  }

  @Override
  public JdbcTemplate jdbc() {
    return jdbc;
  }

  @Override
  public PlatformTransactionManager tm() {
    return tm;
  }

  @Override
  public Map<String, Schema> topics() {
    return Map.of(
        ASSIGNED, NhiemVuDaGiao.getClassSchema(), PROGRESSED, TienDoDaCapNhat.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      if (PROGRESSED.equals(e.topic())) {
        progressed(aggregateId, e.payload(), out);
      } else if (ASSIGNED.equals(e.topic())) {
        assigned(aggregateId, e, out);
      }
    }
    return out.stream().distinct().toList();
  }

  private void progressed(UUID aggregateId, GenericRecord p, List<String> out) {
    UUID progressId = uuid(p, "progressId");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT task_id, period_id, created_by, reported_at, progress_pct, done_quantity"
                + " FROM task.task_progress WHERE id = ?",
            progressId);
    String f = PROGRESSED + ".";
    if (rows.isEmpty()) {
      out.add(f + "progressId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    check(
        out,
        f + "taskId",
        uuid(p, "taskId").equals(aggregateId) && aggregateId.equals(r.get("task_id")));
    check(out, f + "periodId", uuid(p, "periodId").equals(r.get("period_id")));
    check(out, f + "reporterId", uuid(p, "reporterId").equals(r.get("created_by")));
    Instant reported = ((java.sql.Timestamp) r.get("reported_at")).toInstant();
    check(
        out,
        f + "reportedAt",
        micros((Long) p.get("reportedAt")).equals(reported.truncatedTo(ChronoUnit.MICROS)));
    BigDecimal pct = decimal(p, "progressPct");
    BigDecimal ratio = (BigDecimal) r.get("progress_pct");
    check(
        out,
        f + "progressPct",
        pct != null
            && ratio != null
            && pct.movePointLeft(2).setScale(4, RoundingMode.HALF_UP).compareTo(ratio) == 0);
    BigDecimal actual = decimal(p, "actualValue");
    BigDecimal done = (BigDecimal) r.get("done_quantity");
    check(
        out,
        f + "actualValue",
        actual == null ? done == null : done != null && actual.compareTo(done) == 0);
  }

  private void assigned(UUID aggregateId, SourceEvent e, List<String> out) {
    GenericRecord p = e.payload();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT plan_item_id, title, is_key_task, is_restricted, due_date, row_version"
                + " FROM task.task WHERE id = ?",
            aggregateId);
    String f = ASSIGNED + ".";
    if (rows.isEmpty() || !uuid(p, "taskId").equals(aggregateId)) {
      out.add(f + "taskId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    check(out, f + "planItemId", Objects.equals(uuid(p, "planItemId"), r.get("plan_item_id")));
    if (((Number) r.get("row_version")).longValue() != e.aggregateVersion()) {
      return;
    }
    boolean secret = (Boolean) p.get("isSecret");
    check(out, f + "isSecret", secret == (Boolean) r.get("is_restricted"));
    check(out, f + "isKeyTask", (Boolean) p.get("isKeyTask") == (Boolean) r.get("is_key_task"));
    check(
        out,
        f + "dueDate",
        LocalDate.ofEpochDay((Integer) p.get("dueDate"))
            .equals(((Date) r.get("due_date")).toLocalDate()));
    if (!secret) {
      check(out, f + "title", String.valueOf(p.get("title")).equals(r.get("title")));
    }
  }

  private static void check(List<String> out, String field, boolean ok) {
    if (!ok) {
      out.add(field);
    }
  }

  private static UUID uuid(GenericRecord p, String field) {
    Object v = p.get(field);
    return v == null ? null : UUID.fromString(v.toString());
  }

  private static Instant micros(long v) {
    return Instant.EPOCH.plus(v, ChronoUnit.MICROS);
  }

  /** Giá trị decimal (bytes) của trường, kể cả trường union với null. */
  private static BigDecimal decimal(GenericRecord p, String field) {
    Object v = p.get(field);
    if (v == null) {
      return null;
    }
    Schema s = p.getSchema().getField(field).schema();
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
}
