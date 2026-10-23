package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Ghi lô theo TL-03 "Schema scoring": {@code COPY} vào bảng tạm riêng phiên rồi {@code INSERT ...
 * SELECT} sang bảng phân vùng. PostgreSQL không cho {@code COPY FROM} vào bảng bật RLS với vai trò
 * không vượt RLS, nên bảng tạm là chặng đệm; {@code INSERT ... SELECT} vẫn chịu chính sách RLS
 * {@code WITH CHECK} của tenant đang đặt. Không có câu {@code UPDATE} hay {@code DELETE} nào trên
 * {@code score_result}, {@code score_trace}, {@code score_input_v}.
 */
public class CopyScoreResultStore implements ScoreResultStore {

  private static final String SCHEMA = "scoring";

  private static final String INPUT_COLUMNS =
      "id, tenant_id, period_bucket, person_id, flow, input_hash, input_doc, captured_at";
  private static final String RESULT_COLUMNS =
      "id, tenant_id, created_at, created_by, updated_at, updated_by, row_version, period_id,"
          + " period_bucket, score_job_id, person_id, flow, profile_id, input_hash,"
          + " policy_pack_version_id, policy_content_hash, engine_version, result_doc, result_hash,"
          + " status, computed_at";
  private static final String TRACE_COLUMNS =
      "id, tenant_id, period_bucket, score_result_id, step_no, rule_code, legal_ref, inputs, output,"
          + " explanation";

  private static final String VERSIONS_SQL =
      "SELECT r.id, (SELECT count(*) FROM "
          + SCHEMA
          + ".score_result x WHERE x.period_bucket = r.period_bucket AND x.tenant_id = r.tenant_id"
          + " AND x.profile_id = r.profile_id AND (x.computed_at, x.id) <= (r.computed_at, r.id))"
          + " FROM pg_temp.tmp_score_result r";

  private final JdbcTemplate jdbc;

  public CopyScoreResultStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String LATEST_SQL =
      "SELECT DISTINCT ON (profile_id, flow) profile_id, flow, input_hash, policy_content_hash,"
          + " status FROM "
          + SCHEMA
          + ".score_result WHERE period_bucket = ? AND tenant_id = ? AND profile_id = ANY (?)"
          + " ORDER BY profile_id, flow, computed_at DESC, id DESC";

  @Override
  public Set<UUID> unchanged(ScoreBatch batch) {
    UUID[] profiles =
        batch.items().stream().map(ComputedScore::profileId).distinct().toArray(UUID[]::new);
    Map<String, String> latest = new HashMap<>();
    jdbc.query(
        con -> {
          PreparedStatement ps = con.prepareStatement(LATEST_SQL);
          ps.setInt(1, batch.periodBucket());
          ps.setObject(2, batch.tenantId());
          ps.setArray(3, con.createArrayOf("uuid", profiles));
          return ps;
        },
        rs -> {
          if ("OK".equals(rs.getString(5))) {
            latest.put(
                rs.getObject(1, UUID.class) + ":" + rs.getString(2),
                HexFormat.of().formatHex(rs.getBytes(3))
                    + HexFormat.of().formatHex(rs.getBytes(4)));
          }
        });
    String pack = HexFormat.of().formatHex(batch.policyContentHash());
    Set<UUID> out = new HashSet<>();
    for (ComputedScore s : batch.items()) {
      if (s.status() == ScoreStatus.OK
          && (HexFormat.of().formatHex(s.input().hash()) + pack)
              .equals(latest.get(s.profileId() + ":" + s.flow()))) {
        out.add(s.resultId());
      }
    }
    return out;
  }

  @Override
  public Appended append(ScoreBatch batch) {
    return jdbc.execute((ConnectionCallback<Appended>) con -> write(con, batch));
  }

  private Appended write(Connection con, ScoreBatch batch) throws SQLException {
    try (Statement st = con.createStatement()) {
      for (String table : new String[] {"score_input_v", "score_result", "score_trace"}) {
        st.execute(
            "CREATE TEMP TABLE IF NOT EXISTS tmp_"
                + table
                + " (LIKE "
                + SCHEMA
                + "."
                + table
                + ") ON COMMIT DELETE ROWS");
        // Hai lô trong cùng giao dịch không thấy dòng của nhau.
        st.execute("TRUNCATE pg_temp.tmp_" + table);
      }
    }
    copy(con, "tmp_score_input_v", INPUT_COLUMNS, inputRows(batch));
    copy(con, "tmp_score_result", RESULT_COLUMNS, resultRows(batch));
    copy(con, "tmp_score_trace", TRACE_COLUMNS, traceRows(batch));
    int newInputs;
    try (Statement st = con.createStatement()) {
      newInputs =
          st.executeUpdate(
              "INSERT INTO "
                  + SCHEMA
                  + ".score_input_v ("
                  + INPUT_COLUMNS
                  + ") SELECT DISTINCT ON (input_hash) "
                  + INPUT_COLUMNS
                  + " FROM pg_temp.tmp_score_input_v ORDER BY input_hash, id"
                  + " ON CONFLICT (period_bucket, tenant_id, input_hash) DO NOTHING");
      st.executeUpdate(
          "INSERT INTO "
              + SCHEMA
              + ".score_result ("
              + RESULT_COLUMNS
              + ") SELECT "
              + RESULT_COLUMNS
              + " FROM pg_temp.tmp_score_result");
      st.executeUpdate(
          "INSERT INTO "
              + SCHEMA
              + ".score_trace ("
              + TRACE_COLUMNS
              + ") SELECT "
              + TRACE_COLUMNS
              + " FROM pg_temp.tmp_score_trace");
    }
    Map<UUID, Long> versions = new HashMap<>();
    try (PreparedStatement ps = con.prepareStatement(VERSIONS_SQL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        versions.put(rs.getObject(1, UUID.class), rs.getLong(2));
      }
    }
    return new Appended(newInputs, versions);
  }

  private static void copy(Connection con, String table, String columns, String csv)
      throws SQLException {
    if (csv.isEmpty()) {
      return;
    }
    try {
      con.unwrap(PGConnection.class)
          .getCopyAPI()
          .copyIn(
              "COPY pg_temp." + table + " (" + columns + ") FROM STDIN (FORMAT csv)",
              new StringReader(csv));
    } catch (IOException e) {
      throw new SQLException("Không ghi được lô vào " + table, e);
    }
  }

  static String inputRows(ScoreBatch b) {
    StringBuilder out = new StringBuilder();
    for (ComputedScore s : b.items()) {
      row(
          out,
          Ids.newUuidV7(),
          b.tenantId(),
          b.periodBucket(),
          s.personId(),
          s.flow(),
          s.input().hash(),
          s.input().json(),
          b.computedAt());
    }
    return out.toString();
  }

  static String resultRows(ScoreBatch b) {
    StringBuilder out = new StringBuilder();
    for (ComputedScore s : b.items()) {
      row(
          out,
          s.resultId(),
          b.tenantId(),
          b.computedAt(),
          b.actorId(),
          b.computedAt(),
          b.actorId(),
          0,
          b.periodId(),
          b.periodBucket(),
          b.jobId(),
          s.personId(),
          s.flow(),
          s.profileId(),
          s.input().hash(),
          b.policyPackVersionId(),
          b.policyContentHash(),
          b.engineVersion(),
          s.result().json(),
          s.result().hash(),
          s.status().name(),
          b.computedAt());
    }
    return out.toString();
  }

  static String traceRows(ScoreBatch b) {
    StringBuilder out = new StringBuilder();
    for (ComputedScore s : b.items()) {
      for (TraceStep t : s.trace()) {
        row(
            out,
            Ids.newUuidV7(),
            b.tenantId(),
            b.periodBucket(),
            s.resultId(),
            t.stepNo(),
            t.ruleCode(),
            t.legalRef(),
            t.inputs().json(),
            t.output().json(),
            t.explanation());
      }
    }
    return out.toString();
  }

  /** Một dòng CSV: {@code null} là trường rỗng không ngoặc, chuỗi luôn trong ngoặc kép. */
  static void row(StringBuilder out, Object... values) {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      Object v = values[i];
      if (v == null) {
        continue;
      }
      if (v instanceof byte[] bytes) {
        out.append("\\x").append(HexFormat.of().formatHex(bytes));
      } else if (v instanceof String s) {
        out.append('"').append(s.replace("\"", "\"\"")).append('"');
      } else {
        out.append(v);
      }
    }
    out.append('\n');
  }
}
