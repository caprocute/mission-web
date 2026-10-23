package vn.viettel.okrkpi.oversight.infrastructure.reconcile;

import java.sql.Timestamp;
import java.util.ArrayList;
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
import vn.vtokr.oversight.v1.KhieuNaiDaGui;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Nguồn R2 của MOD-07 (DL-12): EVT-22 {@code oversight.period.reopened.v1} so với {@code
 * oversight.reopen_request} và các phiếu đồng ý ở {@code oversight.reopen_approval}; EVT-25 {@code
 * oversight.complaint.filed.v1} so với {@code oversight.complaint}; {@code sys.audit.v1} chỉ đối
 * soát băm.
 *
 * <p>Phạm vi, danh sách người duyệt và nội dung đơn đã gửi không đổi sau sự kiện nên luôn so; hạn
 * trả lời chỉ so khi {@code row_version} còn bằng {@code aggregateVersion} (suy luận: hạn có thể
 * gia hạn khi xử lý).
 */
public class OversightLedgerSource implements LedgerSource {

  static final String REOPENED = EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM;
  static final String COMPLAINT = EventTopics.EVT_25_KHIEU_NAI_DA_GUI;
  static final String AUDIT = EventTopics.SYS_AUDIT_V1;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public OversightLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "oversight";
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
        REOPENED,
        KyDaMoLaiHauKiem.getClassSchema(),
        COMPLAINT,
        KhieuNaiDaGui.getClassSchema(),
        AUDIT,
        AuditEvent.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      switch (e.topic()) {
        case REOPENED -> reopened(aggregateId, LedgerFields.of(e, out));
        case COMPLAINT -> complaint(aggregateId, e, LedgerFields.of(e, out));
        default -> {}
      }
    }
    return LedgerFields.distinct(out);
  }

  private void reopened(UUID requestId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT period_id, scope, scope_ids FROM oversight.reopen_request WHERE id = ?",
            requestId);
    if (rows.isEmpty() || !requestId.equals(f.uuid("reopenRequestId"))) {
      f.missing("reopenRequestId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.sameUuid("periodId", r.get("period_id"));
    f.sameString("scope", r.get("scope"));
    f.sameUuidSet("scopeIds", uuidArray(r.get("scope_ids")));
    List<Map<String, Object>> agreed =
        jdbc.queryForList(
            "SELECT approver_id, decided_at FROM oversight.reopen_approval"
                + " WHERE reopen_request_id = ? AND decision = 'DONG_Y'",
            requestId);
    f.sameUuidSet("approverIds", agreed.stream().map(a -> (UUID) a.get("approver_id")).toList());
    // Thời điểm mở lại là thời điểm của phiếu đồng ý cuối cùng.
    f.sameInstant(
        "executedAt",
        agreed.stream()
            .map(a -> (Timestamp) a.get("decided_at"))
            .max(Timestamp::compareTo)
            .orElse(null));
  }

  private void complaint(UUID complaintId, SourceEvent e, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT profile_id, target_stage, complainant_person_id, filed_at, response_deadline,"
                + " row_version FROM oversight.complaint WHERE id = ? AND filed_at IS NOT NULL",
            complaintId);
    if (rows.isEmpty() || !complaintId.equals(f.uuid("complaintId"))) {
      f.missing("complaintId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.sameUuid("profileId", r.get("profile_id"));
    f.sameString("targetStage", r.get("target_stage"));
    f.sameUuid("complainantId", r.get("complainant_person_id"));
    f.sameInstant("filedAt", r.get("filed_at"));
    if (((Number) r.get("row_version")).longValue() == e.aggregateVersion()) {
      f.sameDate("responseDeadline", r.get("response_deadline"));
    }
  }

  private static List<UUID> uuidArray(Object column) {
    try {
      Object[] values = (Object[]) ((java.sql.Array) column).getArray();
      List<UUID> out = new ArrayList<>(values.length);
      for (Object v : values) {
        out.add((UUID) v);
      }
      return out;
    } catch (java.sql.SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
