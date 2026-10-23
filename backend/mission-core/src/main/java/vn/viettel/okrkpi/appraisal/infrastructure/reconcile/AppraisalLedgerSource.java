package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

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
import vn.vtokr.appraisal.v1.HoSoDanhGiaChuyenBuoc;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;

/**
 * Nguồn R2 của MOD-04 (DL-12), đối tượng là hồ sơ đánh giá {@code appraisal.appraisal_profile}:
 * EVT-11 {@code appraisal.profile.stepcompleted.v1} và EVT-12 {@code appraisal.rating.decided.v1}.
 *
 * <p>Người được đánh giá và luồng không đổi trong đời hồ sơ nên luôn so; trạng thái, xếp loại,
 * người quyết định và ảnh chụp điểm chỉ so khi {@code row_version} còn bằng {@code
 * aggregateVersion} của sự kiện, vì hồ sơ còn chuyển bước hợp lệ sau đó (ký số, khoá, khiếu nại).
 */
public class AppraisalLedgerSource implements LedgerSource {

  static final String STEP = EventTopics.EVT_11_HO_SO_DANH_GIA_CHUYEN_BUOC;
  static final String DECIDED = EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public AppraisalLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "appraisal";
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
        STEP, HoSoDanhGiaChuyenBuoc.getClassSchema(), DECIDED, XepLoaiDaQuyetDinh.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT person_id, flow, status, final_rating_code, decider_id, current_snapshot_id,"
                + " row_version FROM appraisal.appraisal_profile WHERE id = ?",
            aggregateId);
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      LedgerFields f = LedgerFields.of(e, out);
      if (rows.isEmpty() || !aggregateId.equals(f.uuid("profileId"))) {
        f.missing("profileId");
        continue;
      }
      Map<String, Object> r = rows.get(0);
      boolean current = ((Number) r.get("row_version")).longValue() == e.aggregateVersion();
      switch (e.topic()) {
        case STEP -> {
          if (current) {
            f.sameString("toStatus", r.get("status"));
          }
        }
        case DECIDED -> {
          f.sameUuid("personId", r.get("person_id"));
          f.sameString("flow", r.get("flow"));
          if (current) {
            f.sameString("ratingCode", r.get("final_rating_code"));
            f.sameUuid("deciderId", r.get("decider_id"));
            f.sameUuid("scoreSnapshotId", r.get("current_snapshot_id"));
          }
        }
        default -> {}
      }
    }
    return LedgerFields.distinct(out);
  }
}
