package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code scoring} (DV-07-01) ở CSDL pool: RLS và FORCE trên sáu bảng (gồm hàng đợi lô và
 * hàng đợi lỗi của DV-07-06) và các bảng kỹ thuật dùng chung, cô lập hai tenant, phân vùng con chặn
 * truy cập trực tiếp và đủ 16 phân vùng băm.
 */
class ScoringTenantIsolationIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    return List.of("scoring");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "scoring.score_job",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "score_job");
          r.put("job_kind", "CHOT_KY");
          r.put("scope", jsonb("{}"));
          r.put("policy_pack_version_id", id(tenant, "ppv"));
          r.put("total_items", 1);
          return r;
        });
    f.put(
        "scoring.score_input_v",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "score_input_v"));
          r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "DANG");
          r.put("input_hash", hash(seed));
          r.put("input_doc", jsonb("{}"));
          return r;
        });
    f.put(
        "scoring.score_result",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "score_result");
          r.put("score_job_id", id(tenant, "score_job"));
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "DANG");
          r.put("profile_id", id(tenant, "profile"));
          r.put("input_hash", hash(seed));
          r.put("policy_pack_version_id", id(tenant, "ppv"));
          r.put("policy_content_hash", hash(seed));
          r.put("engine_version", "0.1.0");
          r.put("result_doc", jsonb("{}"));
          r.put("result_hash", hash(seed));
          r.put("status", "OK");
          r.put("computed_at", OffsetDateTime.parse("2027-04-01T08:00:00Z"));
          return r;
        });
    f.put(
        "scoring.score_trace",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "score_trace"));
          r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          r.put("score_result_id", id(tenant, "score_result"));
          r.put("step_no", seed);
          r.put("rule_code", "BR-09-01");
          r.put("inputs", jsonb("{}"));
          r.put("output", jsonb("{}"));
          return r;
        });
    f.put(
        "scoring.score_job_chunk",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "score_job_chunk"));
          r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          r.put("score_job_id", id(tenant, "score_job"));
          r.put("seq", seed);
          return r;
        });
    f.put(
        "scoring.score_job_error",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "score_job_error"));
          r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          r.put("score_job_id", id(tenant, "score_job"));
          r.put("chunk_seq", seed);
          r.put("reason_code", "DOC_DAU_VAO_LOI");
          r.put("attempts", 3);
          return r;
        });
    return f;
  }

  private static Map<String, Object> periodRow(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
    r.put("period_id", id(tenant, "period"));
    return r;
  }

  private static byte[] hash(int seed) {
    byte[] b = new byte[32];
    b[0] = (byte) seed;
    return b;
  }

  private static PGobject jsonb(String value) {
    try {
      PGobject o = new PGobject();
      o.setType("jsonb");
      o.setValue(value);
      return o;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
