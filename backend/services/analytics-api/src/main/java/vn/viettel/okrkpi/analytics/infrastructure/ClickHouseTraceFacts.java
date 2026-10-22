package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.TraceFactsPort;

/**
 * Mắt xích điểm, cấu phần, nhiệm vụ của chuỗi truy vết đọc từ kho (DV-15-01) bằng tài khoản Cube:
 * chỉ các cột đã cấp ở V009, mã người giả danh ({@code person_key}) chỉ dùng để nối trong câu truy
 * vấn, không chọn ra.
 */
final class ClickHouseTraceFacts implements TraceFactsPort {

  private static final String SCORE_COLUMNS =
      "SELECT toString(s.id) AS id, toString(s.profile_id) AS profile, toString(s.period_id) AS"
          + " period, toString(s.org_unit_id) AS unit, toString(s.total_score) AS total,"
          + " toString(s.regular_score) AS THUONG_XUYEN, toString(s.key_task_score) AS THEN_CHOT,"
          + " toString(s.general_criteria_score) AS TIEU_CHI_CHUNG, toString(s.kpi_score) AS KPI,"
          + " s.policy_version AS policy, p.name AS period_name"
          + " FROM fact_score AS s FINAL"
          + " LEFT JOIN (SELECT id, name FROM dim_period FINAL WHERE tenant_id = {t:UUID}) AS p"
          + " ON p.id = s.period_id";
  private static final String SCORE =
      SCORE_COLUMNS + " WHERE s.tenant_id = {t:UUID} AND s.id = {id:UUID} LIMIT 1";
  private static final String SCORE_OF_TASK =
      SCORE_COLUMNS
          + " WHERE s.tenant_id = {t:UUID} AND s.is_current = 1 AND (s.person_key, s.period_id) IN"
          + " (SELECT a.person_key, t.period_id FROM fact_task_assignee AS a FINAL"
          + " INNER JOIN (SELECT id, period_id FROM fact_task FINAL"
          + " WHERE tenant_id = {t:UUID} AND id = {id:UUID}) AS t ON t.id = a.task_id"
          + " WHERE a.tenant_id = {t:UUID} AND a.task_id = {id:UUID} AND a.role = 'CHU_TRI'"
          + " AND a.removed_at IS NULL AND a.person_key IS NOT NULL)"
          + " ORDER BY s.computed_at DESC LIMIT 1";
  private static final String TASK_COLUMNS =
      "SELECT toString(t.id) AS id, toString(t.period_id) AS period, toString(a.unit) AS unit,"
          + " t.task_group AS grp, t.is_key_task AS key_task, toString(t.key_result_id) AS kr,"
          + " t.status AS status, toString(t.progress_pct) AS progress"
          + " FROM fact_task AS t FINAL"
          + " LEFT JOIN (SELECT task_id, any(resolved_unit_id) AS unit FROM fact_task_assignee FINAL"
          + " WHERE tenant_id = {t:UUID} AND role = 'CHU_TRI' AND removed_at IS NULL%s"
          + " GROUP BY task_id) AS a ON a.task_id = t.id";
  private static final String TASK =
      TASK_COLUMNS.formatted(" AND task_id = {id:UUID}")
          + " WHERE t.tenant_id = {t:UUID} AND t.id = {id:UUID} LIMIT 1";
  private static final String PROFILE =
      "(SELECT %s FROM fact_appraisal_profile FINAL WHERE tenant_id = {t:UUID} AND id = {p:UUID}"
          + " LIMIT 1)";
  private static final String TASKS_OF_PROFILE =
      TASK_COLUMNS
              .formatted(" AND person_key = " + PROFILE.formatted("person_key"))
              .replace("LEFT JOIN", "INNER JOIN")
          + " WHERE t.tenant_id = {t:UUID} AND t.period_id = "
          + PROFILE.formatted("period_id")
          + " AND t.status NOT IN ('NHAP', 'HUY')"
          + " ORDER BY t.is_key_task DESC, t.code LIMIT {n:UInt32}";
  private static final String EVIDENCES =
      "SELECT toString(id) AS id, toString(deliverable_id) AS deliverable FROM fact_evidence FINAL"
          + " WHERE tenant_id = {t:UUID} AND task_id = {id:UUID} ORDER BY created_at, id"
          + " LIMIT {n:UInt32}";

  /**
   * Tài khoản Cube không được đọc {@code fact_okr_key_result} (V009) nên mục tiêu của KR lấy từ
   * check-in của KR; KR chưa có check-in thì không lần được khi mission-core từ chối KR.
   */
  private static final String OBJECTIVE =
      "SELECT toString(objective_id) AS objective FROM fact_okr_checkin FINAL"
          + " WHERE tenant_id = {t:UUID} AND key_result_id = {id:UUID} LIMIT 1";

  private final ClickHouseHttp ch;

  ClickHouseTraceFacts(ClickHouseHttp ch) {
    this.ch = ch;
  }

  @Override
  public Optional<Score> score(UUID tenant, UUID scoreId) {
    return ch.rows(tenant, SCORE, Map.of("id", scoreId.toString())).stream()
        .findFirst()
        .map(ClickHouseTraceFacts::score);
  }

  @Override
  public Optional<Score> scoreOfTaskOwner(UUID tenant, UUID taskId) {
    return ch.rows(tenant, SCORE_OF_TASK, Map.of("id", taskId.toString())).stream()
        .findFirst()
        .map(ClickHouseTraceFacts::score);
  }

  @Override
  public List<Task> tasksOfProfile(UUID tenant, UUID profileId, int limit) {
    return ch
        .rows(
            tenant, TASKS_OF_PROFILE, Map.of("p", profileId.toString(), "n", String.valueOf(limit)))
        .stream()
        .map(ClickHouseTraceFacts::task)
        .toList();
  }

  @Override
  public Optional<Task> task(UUID tenant, UUID taskId) {
    return ch.rows(tenant, TASK, Map.of("id", taskId.toString())).stream()
        .findFirst()
        .map(ClickHouseTraceFacts::task);
  }

  @Override
  public List<EvidenceRef> evidencesOfTask(UUID tenant, UUID taskId, int limit) {
    return ch
        .rows(tenant, EVIDENCES, Map.of("id", taskId.toString(), "n", String.valueOf(limit)))
        .stream()
        .map(r -> new EvidenceRef(uuid(r, "id"), uuid(r, "deliverable")))
        .toList();
  }

  @Override
  public Optional<UUID> objectiveOfKeyResult(UUID tenant, UUID keyResultId) {
    return ch.rows(tenant, OBJECTIVE, Map.of("id", keyResultId.toString())).stream()
        .findFirst()
        .map(r -> uuid(r, "objective"));
  }

  private static Score score(JsonNode r) {
    LinkedHashMap<String, BigDecimal> parts = new LinkedHashMap<>();
    for (String c : List.of("THUONG_XUYEN", "THEN_CHOT", "TIEU_CHI_CHUNG", "KPI")) {
      BigDecimal v = decimal(r, c);
      if (v != null) {
        parts.put(c, v);
      }
    }
    return new Score(
        uuid(r, "id"),
        uuid(r, "profile"),
        uuid(r, "period"),
        text(r, "period_name"),
        uuid(r, "unit"),
        decimal(r, "total"),
        parts,
        text(r, "policy"));
  }

  private static Task task(JsonNode r) {
    return new Task(
        uuid(r, "id"),
        uuid(r, "period"),
        uuid(r, "unit"),
        text(r, "grp"),
        r.path("key_task").asInt() == 1,
        uuid(r, "kr"),
        text(r, "status"),
        decimal(r, "progress"));
  }

  private static String text(JsonNode r, String f) {
    JsonNode n = r.path(f);
    return n.isNull() || n.isMissingNode() || n.asText().isEmpty() ? null : n.asText();
  }

  private static UUID uuid(JsonNode r, String f) {
    String s = text(r, f);
    return s == null || s.equals("00000000-0000-0000-0000-000000000000")
        ? null
        : UUID.fromString(s);
  }

  private static BigDecimal decimal(JsonNode r, String f) {
    String s = text(r, f);
    return s == null ? null : new BigDecimal(s);
  }
}
