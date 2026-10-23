package vn.viettel.okrkpi.okr.infrastructure.reconcile;

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
import vn.vtokr.okr.v1.KrDaCheckIn;
import vn.vtokr.okr.v1.MucTieuDaDuyet;

/**
 * Nguồn R2 của MOD-01 (DL-12): EVT-01 {@code okr.objective.approved.v1} so với {@code
 * okr.objective}, {@code okr.objective_basis}; EVT-02 {@code okr.keyresult.checkedin.v1} so với
 * {@code okr.kr_checkin}, {@code okr.key_result}.
 *
 * <ul>
 *   <li>EVT-01: kỳ và tầng của mục tiêu không đổi nên luôn so; đơn vị sở hữu, mục tiêu cấp trên,
 *       người duyệt và tập căn cứ chỉ so khi {@code row_version} hiện hành bằng {@code
 *       aggregateVersion} của sự kiện, vì điều chỉnh mục tiêu và cập nhật tiến độ hợp lệ làm tăng
 *       phiên bản (suy luận). Dấu vết nội dung AI chưa so.
 *   <li>EVT-02: check-in đã gửi không sửa (suy luận: không có câu lệnh cập nhật {@code kr_checkin}
 *       sau khi gửi), nên dòng có cùng KR và {@code checkin_at} phải ở {@code DA_GUI} với cùng giá
 *       trị, mức tự tin; KR phải thuộc đúng mục tiêu.
 * </ul>
 */
public class OkrLedgerSource implements LedgerSource {

  static final String APPROVED = EventTopics.EVT_01_MUC_TIEU_DA_DUYET;
  static final String CHECKED_IN = EventTopics.EVT_02_KR_DA_CHECK_IN;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public OkrLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "okr";
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
        APPROVED, MucTieuDaDuyet.getClassSchema(), CHECKED_IN, KrDaCheckIn.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      LedgerFields f = LedgerFields.of(e, out);
      switch (e.topic()) {
        case APPROVED -> approved(aggregateId, e, f);
        case CHECKED_IN -> checkedIn(aggregateId, f);
        default -> {}
      }
    }
    return LedgerFields.distinct(out);
  }

  private void approved(UUID objectiveId, SourceEvent e, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT period_id, tier, owner_unit_id, parent_objective_id, approved_by, row_version"
                + " FROM okr.objective WHERE id = ?",
            objectiveId);
    if (rows.isEmpty() || !objectiveId.equals(f.uuid("objectiveId"))) {
      f.missing("objectiveId");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.sameUuid("periodId", r.get("period_id"));
    f.sameInt("level", r.get("tier"));
    if (((Number) r.get("row_version")).longValue() != e.aggregateVersion()) {
      return;
    }
    f.sameUuid("ownerUnitId", r.get("owner_unit_id"));
    f.sameUuid("parentObjectiveId", r.get("parent_objective_id"));
    f.sameUuid("approvedBy", r.get("approved_by"));
    f.sameUuidSet(
        "basisIds",
        jdbc.queryForList(
            "SELECT basis_id FROM okr.objective_basis WHERE objective_id = ?",
            UUID.class,
            objectiveId));
  }

  private void checkedIn(UUID keyResultId, LedgerFields f) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT c.value, c.confidence, c.status, k.objective_id FROM okr.kr_checkin c"
                + " JOIN okr.key_result k ON k.id = c.key_result_id"
                + " AND k.period_bucket = c.period_bucket"
                + " WHERE c.key_result_id = ? AND c.checkin_at = ?",
            keyResultId,
            java.sql.Timestamp.from(f.micros("checkinAt")));
    if (rows.isEmpty() || !keyResultId.equals(f.uuid("keyResultId"))) {
      f.missing("checkinAt");
      return;
    }
    Map<String, Object> r = rows.get(0);
    f.check("checkinAt", "DA_GUI".equals(r.get("status")));
    f.sameUuid("objectiveId", r.get("objective_id"));
    f.sameDecimal("value", r.get("value"));
    f.sameString("confidence", r.get("confidence"));
  }
}
