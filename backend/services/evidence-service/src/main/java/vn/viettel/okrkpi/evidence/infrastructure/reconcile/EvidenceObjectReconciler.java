package vn.viettel.okrkpi.evidence.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R6 của DL-12 (TL-03 bảng đối soát, dòng "Minh chứng và tệp"): kiểm tra phiên bản tệp {@code
 * evidence.object_version} còn trên Ceph RGW đúng {@code ceph_version_id}, cùng {@code sha256} và
 * {@code size_bytes}. Hằng ngày lấy mẫu tỉ lệ {@code rate} (mặc định 0,1%), mỗi quý quét toàn bộ.
 *
 * <ul>
 *   <li>Mẫu ngày tất định theo ngày giờ Việt Nam ({@code hashtext(id || ngày)}), nên chạy lại cùng
 *       ngày cho cùng mẫu; mã lần chạy tất định theo (tenant, chế độ, ngày hoặc quý).
 *   <li>Đọc phiên bản theo luồng, băm SHA-256 ngay khi đọc, không giữ nội dung; tệp E2EE cũng băm
 *       trên bản mã như lúc tải lên. Đọc kho ngoài giao dịch CSDL.
 *   <li>Xử lý theo TL-03 là cảnh báo và khôi phục từ bản sao (runbook {@code
 *       doi-soat-minh-chung-ceph.md}); job không tự ghi lại tệp vì bucket có Object Lock.
 * </ul>
 */
public class EvidenceObjectReconciler {

  public static final String JOB = "R6_MINH_CHUNG_CEPH";
  public static final String ENTITY = "OBJECT_VERSION";

  /** Số phiên bản lệch ghi chi tiết tối đa vào {@code detail}. */
  static final int DETAIL_LIMIT = 100;

  private static final int PAGE = 500;
  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final Logger log = LoggerFactory.getLogger(EvidenceObjectReconciler.class);

  /** Chế độ chạy: mẫu hằng ngày hay quét toàn bộ hằng quý. */
  public enum Mode {
    NGAY,
    QUY
  }

  /** Tóm tắt một lần chạy. */
  public record Outcome(UUID runId, RunStatus status, int checked, int mismatched) {}

  private record Version(
      UUID id, String bucket, String key, String cephVersion, byte[] sha256, long size) {}

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;
  private final ObjectStorage storage;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;
  private final double rate;
  private final int maxSample;

  public EvidenceObjectReconciler(
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      ObjectStorage storage,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock,
      double rate,
      int maxSample) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (!(rate > 0 && rate <= 1)) {
      throw new IllegalArgumentException("Tỉ lệ lấy mẫu phải trong (0, 1]: " + rate);
    }
    this.rate = rate;
    this.maxSample = maxSample;
  }

  /** Nhãn kỳ chạy: ngày {@code 2026-10-23} hoặc quý {@code 2026-Q4}. */
  public static String period(Mode mode, Instant at) {
    LocalDate d = at.atZone(VN).toLocalDate();
    return mode == Mode.NGAY
        ? d.toString()
        : d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
  }

  public static UUID runId(UUID tenant, Mode mode, String period) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + tenant + "|" + mode + "|" + period).getBytes(StandardCharsets.UTF_8));
  }

  /** Chạy một lần cho tenant; lần cùng kỳ đã có thì bỏ qua. */
  public Outcome run(TenantId tenant, Mode mode) {
    Instant started = clock.instant();
    String period = period(mode, started);
    UUID id = runId(tenant.value(), mode, period);
    ObjectNode start = json.createObjectNode().put("mode", mode.name()).put("period", period);
    if (mode == Mode.NGAY) {
      start.put("rate", rate);
    }
    boolean inserted =
        inTenant(
            tenant,
            () ->
                runs()
                    .insert(
                        ReconcileRun.start(
                            id, tenant.value(), JOB, ENTITY, started, 0, write(start))));
    if (!inserted) {
      return new Outcome(id, RunStatus.DANG_CHAY, 0, 0);
    }
    try {
      return scan(tenant, mode, period, id, started);
    } catch (RuntimeException e) {
      // Không đọc được CSDL giữa chừng: kết thúc LOI để cảnh báo, lần lịch sau (kỳ sau) chạy lại.
      log.warn("Đối soát R6 lỗi: tenant={} chế độ={}", tenant.value(), mode, e);
      Instant now = clock.instant();
      inTenant(
          tenant,
          () -> {
            runs().finish(id, RunStatus.LOI, 0, 0, "{\"reason\": \"KHONG_DOC_DUOC\"}", now);
            return null;
          });
      metrics.finished(JOB, RunStatus.LOI, 0, 0, Duration.between(started, now));
      return new Outcome(id, RunStatus.LOI, 0, 0);
    }
  }

  private Outcome scan(TenantId tenant, Mode mode, String period, UUID id, Instant started) {
    Map<String, Integer> kinds = new TreeMap<>();
    ArrayNode samples = json.createArrayNode();
    int checked = 0;
    UUID after = null;
    while (true) {
      UUID from = after;
      List<Version> page = inTenant(tenant, () -> page(mode, period, from));
      for (Version v : page) {
        String kind = check(v);
        checked++;
        if (kind != null) {
          kinds.merge(kind, 1, Integer::sum);
          if (samples.size() < DETAIL_LIMIT) {
            samples.add(
                json.createObjectNode()
                    .put("versionId", v.id().toString())
                    .put("bucket", v.bucket())
                    .put("key", v.key())
                    .put("cephVersionId", v.cephVersion())
                    .put("kind", kind));
          }
        }
      }
      if (page.size() < PAGE || (mode == Mode.NGAY && checked >= maxSample)) {
        break;
      }
      after = page.get(page.size() - 1).id();
    }
    int mismatched = kinds.values().stream().mapToInt(Integer::intValue).sum();
    RunStatus status = mismatched == 0 ? RunStatus.KHOP : RunStatus.LOI;
    ObjectNode end = json.createObjectNode().put("checked", checked);
    if (mismatched > 0) {
      end.set("kinds", json.valueToTree(kinds));
      end.set("versions", samples);
      log.warn(
          "Đối soát R6 lệch: tenant={} chế độ={} kỳ={} phiên bản lệch={}",
          tenant.value(),
          mode,
          period,
          mismatched);
    }
    Instant now = clock.instant();
    inTenant(
        tenant,
        () -> {
          runs().finish(id, status, mismatched, 0, write(end), now);
          return null;
        });
    metrics.finished(JOB, status, mismatched, 0, Duration.between(started, now));
    log.info("Đối soát R6 xong: tenant={} chế độ={} đã kiểm={}", tenant.value(), mode, checked);
    return new Outcome(id, status, checked, mismatched);
  }

  /** Loại lệch, hoặc {@code null} khi khớp. */
  private String check(Version v) {
    try {
      long[] size = new long[1];
      byte[] got =
          storage.stream(
              new ObjectLocation(v.bucket(), v.key()), v.cephVersion(), in -> digest(in, size));
      if (size[0] != v.size() || !MessageDigest.isEqual(got, v.sha256())) {
        return "LECH_BAM";
      }
      return null;
    } catch (S3Exception e) {
      return e.statusCode() == 404 ? "THIEU_TEP" : "KHONG_DOC_DUOC";
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("Đối soát R6 không đọc được phiên bản {}", v.id(), e);
      return "KHONG_DOC_DUOC";
    }
  }

  private static byte[] digest(InputStream in, long[] size) throws java.io.IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = in.read(buf)) > 0) {
      md.update(buf, 0, n);
      size[0] += n;
    }
    return md.digest();
  }

  private List<Version> page(Mode mode, String period, UUID after) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, bucket, object_key, ceph_version_id, sha256, size_bytes"
                + " FROM evidence.object_version WHERE true");
    Object[] args;
    if (mode == Mode.NGAY) {
      long threshold = Math.max(1, Math.round(rate * 100_000));
      sql.append(" AND (hashtext(id::text || ?) & 2147483647) % 100000 < ?");
      args =
          after == null
              ? new Object[] {period, threshold}
              : new Object[] {period, threshold, after};
    } else {
      args = after == null ? new Object[] {} : new Object[] {after};
    }
    if (after != null) {
      sql.append(" AND id > ?");
    }
    sql.append(" ORDER BY id LIMIT ").append(PAGE);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Version(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBytes(5),
                rs.getLong(6)),
        args);
  }

  private JdbcReconcileRunStore runs() {
    return new JdbcReconcileRunStore(jdbc, "evidence");
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

  private String write(ObjectNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
