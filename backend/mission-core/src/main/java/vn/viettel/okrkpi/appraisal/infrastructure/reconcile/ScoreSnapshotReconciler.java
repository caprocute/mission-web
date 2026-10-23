package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.Buckets;
import vn.viettel.okrkpi.platform.reconcile.HashAggregate;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RowHash;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R3 của DL-12 (TL-03 bảng đối soát, dòng "Điểm và ảnh chụp"): mỗi ảnh chụp {@code
 * appraisal.score_snapshot} của kỳ phải có kết quả {@code scoring.score_result} tương ứng, trạng
 * thái {@code OK}, cùng hồ sơ, cùng {@code input_hash} và {@code policy_content_hash}. Lệch thì ghi
 * {@code LOI} vào {@code appraisal.reconcile_run}, và {@link ScoreReconcileGate} chặn ký số hồ sơ
 * có ảnh chụp hiện hành lệch.
 *
 * <ul>
 *   <li>Ảnh chụp không lưu {@code result_hash} (TL-03 chỉ có ở {@code score_result}), nên R3 so mã
 *       kết quả và hai băm mà EVT-10 mang sang; {@code result_hash} do R4 so khi tính lại (wiki
 *       trang 14).
 *   <li>Chiều ngược (kết quả không có ảnh chụp) không coi là lệch, vì bên nhận bỏ qua có chủ đích
 *       kết quả cũ hơn, hồ sơ đã khoá hay luồng Nhà nước chưa đủ điểm thành phần.
 *   <li>Phía scoring đọc qua một DataSource chỉ đọc riêng (cùng hay khác CSDL đều chạy), theo lô mã
 *       kết quả; hai phía cộng vào băm tổng hợp 256 bucket theo mã hồ sơ, chỉ bucket lệch được đi
 *       sâu (suy luận).
 * </ul>
 */
public class ScoreSnapshotReconciler {

  public static final String JOB = "R3_DIEM_ANH_CHUP";
  public static final String ENTITY = "SCORE_SNAPSHOT";

  /** Số hồ sơ bị chặn ghi tối đa vào {@code detail}; vượt thì chặn cả kỳ. */
  static final int PROFILE_LIMIT = 1_000;

  private static final int CHUNK = 1_000;
  private static final String MISSING = "THIEU_KET_QUA";
  private static final String NOT_OK = "KET_QUA_LOI";
  private static final String DIFFERENT = "LECH_BAM";
  private static final Logger log = LoggerFactory.getLogger(ScoreSnapshotReconciler.class);
  private static final HexFormat HEX = HexFormat.of();

  /** Phía scoring: JdbcTemplate chỉ đọc, giao dịch riêng, tên schema. */
  public record ScoringSource(JdbcTemplate jdbc, PlatformTransactionManager tm, String schema) {
    public ScoringSource {
      Objects.requireNonNull(jdbc, "jdbc");
      Objects.requireNonNull(tm, "tm");
      if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
        throw new IllegalArgumentException("Tên schema scoring không hợp lệ: " + schema);
      }
    }
  }

  /** Tóm tắt một lần đối soát một kỳ của một tenant. */
  public record Outcome(UUID runId, RunStatus status, int checked, List<UUID> blocked) {}

  private record Snap(UUID resultId, UUID profileId, String input, String policy, boolean current) {
    String row() {
      return RowHash.canonical(resultId, profileId, input, policy, "OK");
    }
  }

  private record Result(UUID id, UUID profileId, String input, String policy, String status) {
    String row() {
      return RowHash.canonical(id, profileId, input, policy, status);
    }
  }

  private final JdbcTemplate appraisal;
  private final PlatformTransactionManager appraisalTm;
  private final ScoringSource scoring;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;

  public ScoreSnapshotReconciler(
      JdbcTemplate appraisal,
      PlatformTransactionManager appraisalTm,
      ScoringSource scoring,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock) {
    this.appraisal = Objects.requireNonNull(appraisal, "appraisal");
    this.appraisalTm = Objects.requireNonNull(appraisalTm, "appraisalTm");
    this.scoring = Objects.requireNonNull(scoring, "scoring");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Mã lần chạy tất định theo (tenant, kỳ, phút bắt đầu): hai pod chạy cùng lúc không ghi trùng.
   */
  public static UUID runId(UUID tenant, UUID periodId, Instant started) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + tenant + "|" + periodId + "|" + started.truncatedTo(ChronoUnit.MINUTES))
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Đối soát mọi ảnh chụp của kỳ {@code periodId}; gọi sau bước tính điểm của saga chốt kỳ
   * (DV-06-06) hoặc từ lịch {@link ScoreSnapshotReconcileJob}.
   */
  public Outcome reconcile(TenantId tenant, UUID periodId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(periodId, "periodId");
    Instant started = clock.instant();
    UUID id = runId(tenant.value(), periodId, started);
    ObjectNode start = json.createObjectNode().put("periodId", periodId.toString());
    boolean inserted =
        inAppraisal(
            tenant,
            () ->
                runs()
                    .insert(
                        ReconcileRun.start(
                            id,
                            tenant.value(),
                            JOB,
                            ENTITY,
                            started,
                            Buckets.COUNT,
                            write(start))));
    if (!inserted) {
      return new Outcome(id, RunStatus.DANG_CHAY, 0, List.of());
    }
    try {
      return compare(tenant, periodId, id, started);
    } catch (RuntimeException e) {
      // Không so được (mất kết nối phía scoring...): kết thúc LOI để lần sau chạy lại, không chặn
      // ký số vì chưa biết hồ sơ nào lệch.
      log.warn("Đối soát R3 lỗi: tenant={} kỳ={}", tenant.value(), periodId, e);
      ObjectNode end = json.createObjectNode().put("reason", "KHONG_DOC_DUOC");
      finish(tenant, id, RunStatus.LOI, 0, 0, 0, end, started);
      return new Outcome(id, RunStatus.LOI, 0, List.of());
    }
  }

  private Outcome compare(TenantId tenant, UUID periodId, UUID id, Instant started) {
    HashAggregate expected = new HashAggregate(HashAggregate.Mode.XOR);
    HashAggregate actual = new HashAggregate(HashAggregate.Mode.XOR);
    Map<String, Integer> kinds = new TreeMap<>();
    TreeSet<UUID> blocked = new TreeSet<>();
    int superseded = 0;
    int checked = 0;
    UUID after = null;
    while (true) {
      UUID from = after;
      List<Snap> snaps = inAppraisal(tenant, () -> snapshots(periodId, from));
      if (snaps.isEmpty()) {
        break;
      }
      Map<UUID, Result> results = results(tenant, snaps);
      for (Snap s : snaps) {
        expected.add(s.profileId(), s.row());
        Result r = results.get(s.resultId());
        if (r != null && r.profileId() != null) {
          actual.addTo(Buckets.of(r.profileId()), r.row());
        }
        String kind = kind(s, r);
        if (kind != null) {
          kinds.merge(kind, 1, Integer::sum);
          if (s.current()) {
            blocked.add(s.profileId());
          } else {
            superseded++;
          }
        }
      }
      checked += snaps.size();
      after = snaps.get(snaps.size() - 1).resultId();
    }
    List<Integer> all = new ArrayList<>();
    for (int b = 0; b < Buckets.COUNT; b++) {
      all.add(b);
    }
    Set<Integer> mismatched = Buckets.mismatched(expected.hashes(all), actual.hashes(all));
    int mismatches = kinds.values().stream().mapToInt(Integer::intValue).sum();
    ObjectNode end = json.createObjectNode();
    end.put("checked", checked);
    end.put("bucketsMismatched", mismatched.size());
    if (!kinds.isEmpty()) {
      end.set("kinds", json.valueToTree(kinds));
    }
    if (superseded > 0) {
      end.put("superseded", superseded);
    }
    RunStatus status;
    int repaired = 0;
    if (mismatches == 0) {
      Optional<ReconcileRun> previous = previous(tenant, periodId, started);
      if (previous.isPresent() && previous.get().status() == RunStatus.LOI) {
        status = RunStatus.DA_SUA;
        repaired = blockedCount(previous.get());
      } else {
        status = RunStatus.KHOP;
      }
    } else {
      status = RunStatus.LOI;
      List<String> ids = blocked.stream().limit(PROFILE_LIMIT).map(UUID::toString).toList();
      end.set("profiles", json.valueToTree(ids));
      end.put("blocked", blocked.size());
      if (blocked.size() > PROFILE_LIMIT) {
        end.put("truncated", true);
      }
      log.warn(
          "Đối soát R3 lệch: tenant={} kỳ={} ảnh chụp lệch={} hồ sơ bị chặn ký={}",
          tenant.value(),
          periodId,
          mismatches,
          blocked.size());
    }
    finish(tenant, id, status, mismatched.size(), mismatches, repaired, end, started);
    return new Outcome(id, status, checked, List.copyOf(blocked));
  }

  private static String kind(Snap s, Result r) {
    if (r == null) {
      return MISSING;
    }
    if (!"OK".equals(r.status())) {
      return NOT_OK;
    }
    if (!s.profileId().equals(r.profileId())
        || !s.input().equals(r.input())
        || !s.policy().equals(r.policy())) {
      return DIFFERENT;
    }
    return null;
  }

  private List<Snap> snapshots(UUID periodId, UUID after) {
    return appraisal.query(
        "SELECT score_result_id, profile_id, input_hash, policy_content_hash,"
            + " superseded_by_id IS NULL FROM appraisal.score_snapshot WHERE period_id = ?"
            + (after == null ? "" : " AND score_result_id > ?")
            + " ORDER BY score_result_id LIMIT "
            + CHUNK,
        (rs, i) ->
            new Snap(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                HEX.formatHex(rs.getBytes(3)),
                HEX.formatHex(rs.getBytes(4)),
                rs.getBoolean(5)),
        after == null ? new Object[] {periodId} : new Object[] {periodId, after});
  }

  private Map<UUID, Result> results(TenantId tenant, List<Snap> snaps) {
    UUID[] ids = snaps.stream().map(Snap::resultId).distinct().toArray(UUID[]::new);
    Map<UUID, Result> out = new HashMap<>();
    new TransactionTemplate(scoring.tm())
        .executeWithoutResult(
            st -> {
              // Bảng có RLS: đặt ngữ cảnh tenant trong giao dịch, lọc tenant tường minh.
              scoring
                  .jdbc()
                  .queryForObject(
                      "SELECT set_config('app.tenant_id', ?, true)",
                      String.class,
                      tenant.value().toString());
              scoring
                  .jdbc()
                  .query(
                      "SELECT id, profile_id, input_hash, policy_content_hash, status FROM "
                          + scoring.schema()
                          + ".score_result WHERE tenant_id = ? AND id = ANY (?)",
                      rs -> {
                        UUID rid = rs.getObject(1, UUID.class);
                        out.put(
                            rid,
                            new Result(
                                rid,
                                rs.getObject(2, UUID.class),
                                HEX.formatHex(rs.getBytes(3)),
                                HEX.formatHex(rs.getBytes(4)),
                                rs.getString(5)));
                      },
                      tenant.value(),
                      ids);
            });
    return out;
  }

  private Optional<ReconcileRun> previous(TenantId tenant, UUID periodId, Instant before) {
    return inAppraisal(
        tenant,
        () ->
            appraisal
                .query(
                    "SELECT id FROM appraisal.reconcile_run WHERE job = ? AND status <> 'DANG_CHAY'"
                        + " AND detail ->> 'periodId' = ? AND started_at < ?"
                        + " ORDER BY started_at DESC LIMIT 1",
                    (rs, i) -> rs.getObject(1, UUID.class),
                    JOB,
                    periodId.toString(),
                    Timestamp.from(before))
                .stream()
                .findFirst()
                .flatMap(runs()::find));
  }

  private int blockedCount(ReconcileRun run) {
    try {
      return json.readTree(run.detail()).path("blocked").asInt(0);
    } catch (JsonProcessingException e) {
      return 0;
    }
  }

  private void finish(
      TenantId tenant,
      UUID id,
      RunStatus status,
      int mismatched,
      int mismatches,
      int repaired,
      ObjectNode end,
      Instant started) {
    Instant now = clock.instant();
    inAppraisal(
        tenant,
        () -> {
          runs().finish(id, status, mismatched, repaired, write(end), now);
          return null;
        });
    metrics.finished(JOB, status, mismatches, repaired, Duration.between(started, now));
  }

  private JdbcReconcileRunStore runs() {
    return new JdbcReconcileRunStore(appraisal, "appraisal");
  }

  private <T> T inAppraisal(TenantId tenant, Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(appraisalTm)
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

  private String write(ObjectNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
