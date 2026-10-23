package vn.viettel.okrkpi.scoring.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R4 của DL-12 (TL-03 bảng đối soát, dòng "Tái lập điểm"): hằng tuần lấy mẫu tỉ lệ {@code rate}
 * (mặc định 0,5%) kết quả {@code OK} của các tác vụ {@code CHOT_KY} đã xong, mở cho mỗi tác vụ gốc
 * một tác vụ {@code KIEM_TRA_TAI_LAP} cùng phiên bản quy tắc; khi bộ máy tính (DV-07-06) tính xong,
 * so {@code result_hash} của kết quả tính lại với kết quả gốc theo hồ sơ.
 *
 * <ul>
 *   <li>Mẫu tất định theo tuần ISO: {@code hashtext(id || tuần) mod 10000 < rate * 10000}, nên chạy
 *       lại cùng tuần cho cùng mẫu; mã sự kiện kích hoạt tất định theo (tuần, tác vụ gốc) nên không
 *       mở trùng tác vụ.
 *   <li>Phạm vi tác vụ kiểm tra: {@code {"recheckOf": <tác vụ gốc>, "results": [<mã kết quả>]}}; bộ
 *       máy tính đọc {@code score_input_v} theo {@code input_hash} của từng kết quả (suy luận,
 *       DV-07-06 hiện thực). Bên nhận EVT-10 bỏ qua loại này nên không tạo ảnh chụp.
 *   <li>Mỗi tenant mỗi tuần một dòng {@code scoring.reconcile_run}: {@code DANG_CHAY} khi mở, rồi
 *       {@code KHOP}, hoặc {@code LOI} khi lệch băm hay quá {@code timeout} mà chưa tính xong. Xử
 *       lý theo TL-03 là cảnh báo và điều tra phiên bản bộ máy tính, không tự sửa.
 * </ul>
 */
public class ScoreRecomputeReconciler {

  public static final String JOB = "R4_TAI_LAP_DIEM";
  public static final String ENTITY = "SCORE_RESULT";

  /** Số kết quả lệch ghi chi tiết tối đa vào {@code detail}. */
  static final int DETAIL_LIMIT = 100;

  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  private static final Logger log = LoggerFactory.getLogger(ScoreRecomputeReconciler.class);

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;
  private final ScoreJobStore jobs;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;
  private final double rate;
  private final int maxPerJob;
  private final Duration lookback;
  private final Duration timeout;

  public ScoreRecomputeReconciler(
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      ScoreJobStore jobs,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock,
      double rate,
      int maxPerJob,
      Duration lookback,
      Duration timeout) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (!(rate > 0 && rate <= 1)) {
      throw new IllegalArgumentException("Tỉ lệ lấy mẫu phải trong (0, 1]: " + rate);
    }
    this.rate = rate;
    this.maxPerJob = maxPerJob;
    this.lookback = Objects.requireNonNull(lookback, "lookback");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /** Tuần ISO theo giờ Việt Nam, ví dụ {@code 2026-W43}. */
  public static String week(Instant at) {
    var d = at.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
    return d.get(IsoFields.WEEK_BASED_YEAR)
        + "-W"
        + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  public static UUID runId(UUID tenant, String week) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + tenant + "|" + week).getBytes(StandardCharsets.UTF_8));
  }

  static UUID triggerId(UUID tenant, String week, UUID originalJob) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + tenant + "|" + week + "|" + originalJob).getBytes(StandardCharsets.UTF_8));
  }

  /** Mở mẫu tuần này cho tenant; trả số kết quả đã lấy mẫu (0 nếu đã mở hoặc không có gì). */
  public int sample(TenantId tenant) {
    Instant now = clock.instant();
    String week = week(now);
    UUID id = runId(tenant.value(), week);
    return inTenant(
        tenant,
        () -> {
          if (runs().find(id).isPresent()) {
            return 0;
          }
          long threshold = Math.max(1, Math.round(rate * 10_000));
          List<Map<String, Object>> originals =
              jdbc.queryForList(
                  "SELECT id, period_id, period_bucket, policy_pack_version_id FROM"
                      + " scoring.score_job WHERE job_kind = 'CHOT_KY' AND status = 'XONG'"
                      + " AND finished_at >= ? ORDER BY finished_at",
                  Timestamp.from(now.minus(lookback)));
          ArrayNode checks = json.createArrayNode();
          int sampled = 0;
          for (Map<String, Object> o : originals) {
            UUID job = (UUID) o.get("id");
            int bucket = ((Number) o.get("period_bucket")).intValue();
            List<UUID> ids =
                jdbc.queryForList(
                    "SELECT id FROM scoring.score_result WHERE period_bucket = ? AND score_job_id = ?"
                        + " AND status = 'OK' AND (hashtext(id::text || ?) & 2147483647) % 10000 < ?"
                        + " ORDER BY id LIMIT ?",
                    UUID.class, bucket, job, week, threshold, maxPerJob);
            if (ids.isEmpty()) {
              continue;
            }
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("recheckOf", job.toString());
            scope.put("results", ids.stream().map(UUID::toString).toList());
            UUID check =
                jobs.open(
                    new ScoreJob(
                        tenant.value(),
                        UUID.randomUUID(),
                        (UUID) o.get("period_id"),
                        bucket,
                        ScoreJobKind.KIEM_TRA_TAI_LAP,
                        CanonicalDoc.of(scope),
                        (UUID) o.get("policy_pack_version_id"),
                        triggerId(tenant.value(), week, job),
                        ids.size(),
                        SYSTEM_ACTOR));
            checks.add(
                json.createObjectNode()
                    .put("job", check.toString())
                    .put("bucket", bucket)
                    .put("recheckOf", job.toString())
                    .put("sampled", ids.size()));
            sampled += ids.size();
          }
          ObjectNode detail = json.createObjectNode().put("week", week).put("rate", rate);
          detail.set("checks", checks);
          runs()
              .insert(
                  ReconcileRun.start(
                      id, tenant.value(), JOB, ENTITY, now, checks.size(), write(detail)));
          if (checks.isEmpty()) {
            // Không có kết quả đã chốt trong phạm vi: kết thúc ngay để cảnh báo "ngừng chạy" không
            // bật.
            runs().finish(id, RunStatus.KHOP, 0, 0, "{\"sampled\": 0}", now);
            metrics.finished(JOB, RunStatus.KHOP, 0, 0, Duration.ZERO);
          }
          return sampled;
        });
  }

  /** So các lần chạy đang chờ của tenant; trả số lần chạy đã kết thúc. */
  public int evaluate(TenantId tenant) {
    Instant now = clock.instant();
    List<ReconcileRun> pending =
        inTenant(tenant, () -> runs().pending(JOB, now.plusSeconds(1), 50));
    int done = 0;
    for (ReconcileRun run : pending) {
      try {
        if (inTenant(tenant, () -> evaluate(run, now))) {
          done++;
        }
      } catch (RuntimeException e) {
        log.warn("Đối soát R4 lỗi: tenant={} lần chạy={}", tenant.value(), run.id(), e);
      }
    }
    return done;
  }

  private boolean evaluate(ReconcileRun run, Instant now) {
    JsonNode detail = read(run.detail());
    int checked = 0;
    int unfinished = 0;
    List<ObjectNode> mismatches = new ArrayList<>();
    int mismatchCount = 0;
    for (JsonNode c : detail.path("checks")) {
      UUID check = UUID.fromString(c.path("job").asText());
      int bucket = c.path("bucket").asInt();
      String status =
          jdbc
              .queryForList(
                  "SELECT status FROM scoring.score_job WHERE period_bucket = ? AND id = ?",
                  String.class,
                  bucket,
                  check)
              .stream()
              .findFirst()
              .orElse("CHO");
      if (!"XONG".equals(status)) {
        unfinished++;
        continue;
      }
      // Kết quả gốc trong phạm vi và kết quả tính lại của cùng hồ sơ trong tác vụ kiểm tra.
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "SELECT o.id AS original, o.engine_version AS engine_from, r.id AS recheck,"
                  + " r.engine_version AS engine_to, r.status AS status,"
                  + " o.result_hash = r.result_hash AS same"
                  + " FROM scoring.score_result o"
                  + " LEFT JOIN scoring.score_result r ON r.period_bucket = o.period_bucket"
                  + " AND r.tenant_id = o.tenant_id AND r.score_job_id = ? AND r.profile_id = o.profile_id"
                  + " WHERE o.period_bucket = ? AND o.score_job_id = ? AND o.id = ANY (?)",
              check,
              bucket,
              UUID.fromString(c.path("recheckOf").asText()),
              ids(check, bucket));
      for (Map<String, Object> r : rows) {
        checked++;
        if (!Boolean.TRUE.equals(r.get("same")) || !"OK".equals(r.get("status"))) {
          mismatchCount++;
          if (mismatches.size() < DETAIL_LIMIT) {
            ObjectNode m =
                json.createObjectNode().put("resultId", String.valueOf(r.get("original")));
            m.put("recheckId", r.get("recheck") == null ? null : r.get("recheck").toString());
            m.put("engineFrom", (String) r.get("engine_from"));
            m.put("engineTo", (String) r.get("engine_to"));
            mismatches.add(m);
          }
        }
      }
    }
    boolean expired = run.startedAt().plus(timeout).isBefore(now);
    if (unfinished > 0 && !expired) {
      return false;
    }
    ObjectNode end = json.createObjectNode().put("checked", checked);
    RunStatus status = RunStatus.KHOP;
    if (mismatchCount > 0) {
      status = RunStatus.LOI;
      end.put("mismatched", mismatchCount);
      end.set("mismatches", json.valueToTree(mismatches));
    }
    if (unfinished > 0) {
      status = RunStatus.LOI;
      end.put("reason", "CHUA_TINH_LAI_XONG");
      end.put("unfinished", unfinished);
    }
    if (status == RunStatus.LOI) {
      log.warn(
          "Đối soát R4 lệch: tenant={} tuần={} kết quả lệch={} tác vụ chưa xong={}",
          run.tenantId(),
          detail.path("week").asText(),
          mismatchCount,
          unfinished);
    }
    runs().finish(run.id(), status, mismatchCount > 0 ? 1 : 0, 0, write(end), now);
    metrics.finished(JOB, status, mismatchCount, 0, Duration.between(run.startedAt(), now));
    return true;
  }

  /** Mã kết quả gốc của tác vụ kiểm tra, đọc từ phạm vi đã ghi. */
  private UUID[] ids(UUID check, int bucket) {
    String scope =
        jdbc.queryForObject(
            "SELECT scope::text FROM scoring.score_job WHERE period_bucket = ? AND id = ?",
            String.class,
            bucket,
            check);
    List<UUID> out = new ArrayList<>();
    for (JsonNode id : read(scope).path("results")) {
      out.add(UUID.fromString(id.asText()));
    }
    return out.toArray(UUID[]::new);
  }

  private JdbcReconcileRunStore runs() {
    return new JdbcReconcileRunStore(jdbc, "scoring");
  }

  private <T> T inTenant(TenantId tenant, Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(tm)
                  .execute(
                      st -> {
                        try {
                          return work.call();
                        } catch (RuntimeException e) {
                          throw e;
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode read(String s) {
    try {
      return json.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String write(ObjectNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
