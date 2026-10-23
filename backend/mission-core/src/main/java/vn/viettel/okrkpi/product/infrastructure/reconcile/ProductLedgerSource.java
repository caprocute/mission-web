package vn.viettel.okrkpi.product.infrastructure.reconcile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerFields;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource;
import vn.vtokr.product.v1.MinhChungDaNop;
import vn.vtokr.product.v1.MinhChungDaXacNhan;

/**
 * Nguồn R2 của MOD-03 (DL-12), đối tượng là phiên bản minh chứng: EVT-07 {@code
 * product.evidence.submitted.v1} so với {@code product.evidence_version}, {@code product.evidence}
 * và tệp đầu tiên ở {@code product.evidence_version_file}; EVT-08 {@code
 * product.evidence.accepted.v1} so với quyết định {@code DA_XAC_NHAN} ở {@code product.acceptance}.
 *
 * <p>Phiên bản đã nộp, tệp của nó và quyết định xác nhận không sửa được theo luồng nghiệp vụ
 * (trigger {@code fn_freeze_evidence_version}, {@code fn_freeze_evidence_version_file}), nên mọi
 * trường đều so. {@code aiConfirmed} đúng khi phiên bản không do AI điền sẵn hoặc người dùng đã xác
 * nhận.
 */
public class ProductLedgerSource implements LedgerSource {

  static final String SUBMITTED = EventTopics.EVT_07_MINH_CHUNG_DA_NOP;
  static final String ACCEPTED = EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public ProductLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "product";
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
        SUBMITTED, MinhChungDaNop.getClassSchema(), ACCEPTED, MinhChungDaXacNhan.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      LedgerFields f = LedgerFields.of(e, out);
      if (!aggregateId.equals(f.uuid("versionId"))) {
        f.missing("versionId");
        continue;
      }
      switch (e.topic()) {
        case SUBMITTED -> submitted(aggregateId, f);
        case ACCEPTED -> accepted(aggregateId, f);
        default -> {}
      }
    }
    return LedgerFields.distinct(out);
  }

  private void submitted(UUID versionId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT v.evidence_id, v.task_id, v.status, v.submitted_by, v.ai_prefill,"
                + " v.user_confirmed_at, e.deliverable_id,"
                + " (SELECT fl.content_sha256 FROM product.evidence_version_file fl"
                + " WHERE fl.period_bucket = v.period_bucket AND fl.evidence_version_id = v.id ORDER BY fl.ordinal LIMIT 1) AS sha"
                + " FROM product.evidence_version v JOIN product.evidence e ON e.id = v.evidence_id"
                + " AND e.period_bucket = v.period_bucket WHERE v.id = ?",
            versionId);
    if (rows.isEmpty()) {
      f.missing("versionId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.check("versionId", !"NHAP".equals(r.get("status")));
    f.sameUuid("evidenceId", r.get("evidence_id"));
    f.sameUuid("deliverableId", r.get("deliverable_id"));
    f.sameUuid("taskId", r.get("task_id"));
    f.sameUuid("submittedBy", r.get("submitted_by"));
    f.check("objectSha256", Arrays.equals(f.bytes("objectSha256"), (byte[]) r.get("sha")));
    boolean confirmed =
        !Boolean.TRUE.equals(r.get("ai_prefill")) || r.get("user_confirmed_at") != null;
    f.check("aiConfirmed", Objects.equals(f.bool("aiConfirmed"), confirmed));
  }

  private void accepted(UUID versionId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT a.task_id, a.reviewer_id, a.quality_level, a.decided_at, v.evidence_id,"
                + " v.status FROM product.acceptance a JOIN product.evidence_version v"
                + " ON v.id = a.evidence_version_id AND v.period_bucket = a.period_bucket"
                + " WHERE a.evidence_version_id = ? AND a.decision = 'DA_XAC_NHAN'",
            versionId);
    if (rows.isEmpty()) {
      f.missing("versionId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.check("versionId", "DA_XAC_NHAN".equals(r.get("status")));
    f.sameUuid("evidenceId", r.get("evidence_id"));
    f.sameUuid("taskId", r.get("task_id"));
    f.sameUuid("acceptedBy", r.get("reviewer_id"));
    f.sameDecimal("qualityLevel", r.get("quality_level"));
    f.sameInstant("acceptedAt", r.get("decided_at"));
  }
}
