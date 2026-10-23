package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link ProspectBoardRepository} trên {@code appraisalDataSource} (tài khoản {@code
 * svc_appraisal}, tài khoản duy nhất đọc được {@code group_code}, {@code suggested_group}). Người
 * trên bảng là người có hồ sơ năm ở đơn vị; mức năm lấy mức nặng nhất giữa hai luồng (suy luận,
 * wiki 14).
 */
public class JdbcProspectBoardRepository implements ProspectBoardRepository {

  private static final String BOARD_COLUMNS =
      "id, period_id, period_bucket, org_unit_id, status, board_version, row_version,"
          + " rejection_reason_enc, dek_id, sign_request_id, updated_by";

  private static final String CANDIDATES_SQL =
      """
      SELECT p.person_id, max(pv.full_name),
             (array_agg(coalesce(ar.rating_code, ar.system_level)
                ORDER BY CASE coalesce(ar.rating_code, ar.system_level)
                           WHEN 'KHTNV' THEN 0 WHEN 'HTNV' THEN 1 WHEN 'HTTNV' THEN 2
                           WHEN 'HTXSNV' THEN 3 ELSE 4 END, p.flow))[1],
             coalesce(bool_or(ar.reassign_flag), false),
             array_agg(p.id ORDER BY p.flow),
             min(p.flow),
             (array_agg(p.policy_pack_version_id ORDER BY p.flow))[1]
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.annual_rating ar
          ON ar.period_bucket = p.period_bucket AND ar.tenant_id = p.tenant_id
         AND ar.profile_id = p.id
        LEFT JOIN org_replica.person_v pv ON pv.tenant_id = p.tenant_id AND pv.id = p.person_id
       WHERE p.period_bucket = ? AND p.tenant_id = ? AND p.period_id = ? AND p.org_unit_id = ?
       GROUP BY p.person_id
       ORDER BY max(pv.full_name) NULLS LAST, p.person_id
      """;

  private static final String UPSERT_ENTRY_SQL =
      """
      INSERT INTO appraisal.prospect_group (id, created_by, updated_by, period_id, period_bucket,
             person_id, group_code, rank_in_group, reason_enc, dek_id, training_plan_ref)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (period_bucket, tenant_id, person_id) DO UPDATE
         SET group_code = EXCLUDED.group_code, rank_in_group = EXCLUDED.rank_in_group,
             reason_enc = EXCLUDED.reason_enc,
             dek_id = CASE WHEN EXCLUDED.reason_enc IS NULL
                                AND prospect_group.training_plan_enc IS NOT NULL
                           THEN prospect_group.dek_id ELSE EXCLUDED.dek_id END,
             training_plan_ref = EXCLUDED.training_plan_ref, updated_by = EXCLUDED.updated_by,
             updated_at = now(), row_version = prospect_group.row_version + 1
      """;

  private static final String SIGN_ENTRIES_SQL =
      """
      UPDATE appraisal.prospect_group g
         SET decided_by = ?, decided_at = ?, updated_by = ?, updated_at = now(),
             row_version = g.row_version + 1
       WHERE g.period_bucket = ? AND g.tenant_id = ?
         AND EXISTS (SELECT 1 FROM appraisal.appraisal_profile p
                      WHERE p.period_bucket = g.period_bucket AND p.tenant_id = g.tenant_id
                        AND p.person_id = g.person_id AND p.period_id = ? AND p.org_unit_id = ?)
      """;

  private final JdbcClient jdbc;

  public JdbcProspectBoardRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<Board> find(TenantId tenant, int bucket, UUID orgUnitId) {
    return jdbc.sql(
            "SELECT "
                + BOARD_COLUMNS
                + " FROM appraisal.prospect_board"
                + " WHERE period_bucket = ? AND tenant_id = ? AND org_unit_id = ?")
        .params(bucket, tenant.value(), orgUnitId)
        .query((rs, i) -> board(rs))
        .optional();
  }

  @Override
  public Board lockOrCreate(
      TenantId tenant, UUID periodId, int bucket, UUID orgUnitId, UUID actor) {
    jdbc.sql(
            "INSERT INTO appraisal.prospect_board (id, created_by, updated_by, period_id,"
                + " period_bucket, org_unit_id) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (period_bucket, tenant_id, org_unit_id) DO NOTHING")
        .params(UUID.randomUUID(), actor, actor, periodId, bucket, orgUnitId)
        .update();
    return jdbc.sql(
            "SELECT "
                + BOARD_COLUMNS
                + " FROM appraisal.prospect_board"
                + " WHERE period_bucket = ? AND tenant_id = ? AND org_unit_id = ? FOR UPDATE")
        .params(bucket, tenant.value(), orgUnitId)
        .query((rs, i) -> board(rs))
        .single();
  }

  @Override
  public List<Candidate> candidates(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId) {
    return jdbc.sql(CANDIDATES_SQL)
        .params(bucket, tenant.value(), periodId, orgUnitId)
        .query(
            (rs, i) ->
                new Candidate(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    uuids(rs.getArray(5)),
                    rs.getString(6),
                    rs.getObject(7, UUID.class)))
        .list();
  }

  @Override
  public List<Entry> entries(TenantId tenant, int bucket, List<UUID> personIds) {
    if (personIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT id, person_id, group_code, suggested_group, rank_in_group, reason_enc, dek_id,"
                + " training_plan_ref, decided_at FROM appraisal.prospect_group"
                + " WHERE period_bucket = ? AND tenant_id = ? AND person_id = ANY(?::uuid[])")
        .params(bucket, tenant.value(), personIds.toArray(UUID[]::new))
        .query(
            (rs, i) ->
                new Entry(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    (Integer) rs.getObject(5),
                    sealed(rs.getBytes(6), rs.getObject(7, UUID.class)),
                    rs.getString(8),
                    instant(rs.getTimestamp(9))))
        .list();
  }

  @Override
  public void saveEntries(
      TenantId tenant, UUID periodId, int bucket, List<EntryWrite> rows, UUID actor) {
    for (EntryWrite r : rows) {
      jdbc.sql(UPSERT_ENTRY_SQL)
          .params(
              r.id(),
              actor,
              actor,
              periodId,
              bucket,
              r.personId(),
              r.groupCode(),
              r.rankInGroup(),
              r.reason() == null ? null : r.reason().cipher(),
              r.reason() == null ? null : r.reason().dekId(),
              r.trainingPlanRef())
          .update();
    }
  }

  @Override
  public long touch(TenantId tenant, int bucket, UUID boardId, UUID actor) {
    return update(tenant, bucket, boardId, "updated_by = ?", actor);
  }

  @Override
  public long markSubmitted(TenantId tenant, int bucket, UUID boardId, UUID actor) {
    return update(
        tenant,
        bucket,
        boardId,
        "status = 'DA_TRINH', submitted_by = ?, submitted_at = now(), updated_by = ?",
        actor,
        actor);
  }

  @Override
  public void recordProcess(TenantId tenant, int bucket, UUID boardId, String processInstanceId) {
    jdbc.sql(
            "UPDATE appraisal.prospect_board SET process_instance_id = ?"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(processInstanceId, bucket, tenant.value(), boardId)
        .update();
  }

  @Override
  public long markRejected(
      TenantId tenant, int bucket, UUID boardId, SealedText reason, UUID actor) {
    return jdbc.sql(
            "UPDATE appraisal.prospect_board SET status = 'NHAP', board_version = board_version + 1,"
                + " rejection_reason_enc = ?, dek_id = ?, process_instance_id = NULL,"
                + " sign_request_id = NULL, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? RETURNING row_version")
        .params(
            reason == null ? null : reason.cipher(),
            reason == null ? null : reason.dekId(),
            actor,
            bucket,
            tenant.value(),
            boardId)
        .query(Long.class)
        .single();
  }

  @Override
  public long markApproved(
      TenantId tenant, int bucket, UUID boardId, UUID signRequestId, UUID actor) {
    return jdbc.sql(
            "UPDATE appraisal.prospect_board SET sign_request_id = ?, updated_by = ?,"
                + " updated_at = now(), row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? RETURNING row_version")
        .params(signRequestId, actor, bucket, tenant.value(), boardId)
        .query(Long.class)
        .single();
  }

  @Override
  public Optional<Board> findBySignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT "
                + BOARD_COLUMNS
                + " FROM appraisal.prospect_board WHERE tenant_id = ? AND sign_request_id = ?"
                + " FOR UPDATE")
        .params(tenant.value(), signRequestId)
        .query((rs, i) -> board(rs))
        .optional();
  }

  @Override
  public long markSigned(TenantId tenant, Board board, Instant signedAt) {
    Timestamp at = Timestamp.from(signedAt);
    long version =
        jdbc.sql(
                "UPDATE appraisal.prospect_board SET status = 'DA_KY', signed_at = ?,"
                    + " updated_at = now(), row_version = row_version + 1"
                    + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? RETURNING row_version")
            .params(at, board.periodBucket(), tenant.value(), board.id())
            .query(Long.class)
            .single();
    jdbc.sql(SIGN_ENTRIES_SQL)
        .params(
            board.updatedBy(),
            at,
            board.updatedBy(),
            board.periodBucket(),
            tenant.value(),
            board.periodId(),
            board.orgUnitId())
        .update();
    return version;
  }

  private long update(TenantId tenant, int bucket, UUID boardId, String set, Object... values) {
    List<Object> params = new ArrayList<>(List.of(values));
    params.addAll(List.of(bucket, tenant.value(), boardId));
    return jdbc.sql(
            "UPDATE appraisal.prospect_board SET "
                + set
                + ", updated_at = now(), row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? RETURNING row_version")
        .params(params)
        .query(Long.class)
        .single();
  }

  private static Board board(ResultSet rs) throws SQLException {
    return new Board(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getObject(4, UUID.class),
        rs.getString(5),
        rs.getInt(6),
        rs.getLong(7),
        sealed(rs.getBytes(8), rs.getObject(9, UUID.class)),
        rs.getObject(10, UUID.class),
        rs.getObject(11, UUID.class));
  }

  private static SealedText sealed(byte[] cipher, UUID dek) {
    return cipher == null ? null : new SealedText(cipher, dek);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    List<UUID> out = new ArrayList<>();
    if (array != null) {
      for (Object o : (Object[]) array.getArray()) {
        out.add((UUID) o);
      }
    }
    return out;
  }
}
