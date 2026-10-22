package vn.viettel.okrkpi.platform.keyrotation;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.crypto.DekCache;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;

/**
 * Job đầu tháng (TL-03 chương 9 "Mã hoá phong bì cho cột C2", ADR-30): với mỗi (tenant, mục đích)
 * đang dùng, sinh trước DEK {@code ACTIVE} của tháng mới qua {@link DekService} (chèn {@code ON
 * CONFLICT DO NOTHING} nên chạy lại không sinh trùng), rồi chuyển các DEK {@code ACTIVE} của tháng
 * trước sang {@code DECRYPT_ONLY}. Khoá gốc chỉ mục mù ({@link DekPurpose#BLIND_INDEX}, tháng neo
 * 1970-01-01) không xoay theo tháng. {@code DekCache} 15 phút của ứng dụng tự hết hạn nên không cần
 * khởi động lại pod. Kết nối bằng {@code svc_keyring}, vai trò duy nhất được đổi trạng thái DEK.
 */
public class MonthlyDekRotationJob {

  static final String JOB = "dek_thang";

  /** Kết quả một lần chạy. */
  public record Result(int tenants, int created, int demoted) {}

  private final JdbcTemplate keyring;
  private final DekService dekService;
  private final TenantSource tenants;
  private final RotationReporter reporter;
  private final Clock clock;

  public MonthlyDekRotationJob(
      JdbcTemplate keyring,
      DekService dekService,
      TenantSource tenants,
      RotationReporter reporter,
      Clock clock) {
    this.keyring = Objects.requireNonNull(keyring, "keyring");
    this.dekService = Objects.requireNonNull(dekService, "dekService");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.reporter = Objects.requireNonNull(reporter, "reporter");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Chạy cho tháng hiện hành theo đồng hồ. */
  public Result run() {
    return run(YearMonth.now(clock));
  }

  public Result run(YearMonth month) {
    LocalDate first = month.atDay(1);
    int created = 0;
    int demoted = 0;
    Map<DekPurpose, Long> maxAge = new EnumMap<>(DekPurpose.class);
    List<UUID> all = tenants.tenants();
    for (UUID tenant : all) {
      List<DekPurpose> inUse = purposesInUse(tenant, first);
      int tenantCreated = 0;
      for (DekPurpose p : inUse) {
        boolean existed = exists(tenant, p, first);
        DekCache.CachedDek dek = dekService.getOrCreateActiveDek(tenant, p, first);
        DekCache.zeroKey(dek.keyBytes());
        if (!existed) {
          tenantCreated++;
        }
      }
      int tenantDemoted = demote(tenant, first);
      created += tenantCreated;
      demoted += tenantDemoted;
      ages(tenant, maxAge);
      reporter.audit(
          "KEY_ROTATION_DEK_MONTHLY",
          tenant,
          null,
          null,
          Map.of(
              "thang", month.toString(),
              "so_dek_moi", Integer.toString(tenantCreated),
              "so_dek_chi_giai_ma", Integer.toString(tenantDemoted)));
    }
    maxAge.forEach((p, d) -> reporter.keyAge(p.name(), d));
    reporter.items(JOB, "dek_moi", created);
    reporter.items(JOB, "dek_chi_giai_ma", demoted);
    return new Result(all.size(), created, demoted);
  }

  private List<DekPurpose> purposesInUse(UUID tenant, LocalDate first) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          List<DekPurpose> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT DISTINCT purpose FROM keyring.dek WHERE tenant_id = ?"
                      + " AND purpose <> 'BLIND_INDEX' AND status = 'ACTIVE' AND valid_month < ?"
                      + " UNION SELECT purpose FROM keyring.dek WHERE tenant_id = ?"
                      + " AND purpose <> 'BLIND_INDEX' AND valid_month = ?")) {
            ps.setObject(1, tenant);
            ps.setDate(2, Date.valueOf(first));
            ps.setObject(3, tenant);
            ps.setDate(4, Date.valueOf(first));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(DekPurpose.valueOf(rs.getString(1)));
              }
            }
          }
          return out;
        });
  }

  private boolean exists(UUID tenant, DekPurpose p, LocalDate first) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT 1 FROM keyring.dek WHERE tenant_id = ? AND purpose = ? AND valid_month = ?")) {
            ps.setObject(1, tenant);
            ps.setString(2, p.name());
            ps.setDate(3, Date.valueOf(first));
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        });
  }

  private int demote(UUID tenant, LocalDate first) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE keyring.dek SET status = 'DECRYPT_ONLY' WHERE tenant_id = ?"
                      + " AND status = 'ACTIVE' AND purpose <> 'BLIND_INDEX' AND valid_month < ?")) {
            ps.setObject(1, tenant);
            ps.setDate(2, Date.valueOf(first));
            return ps.executeUpdate();
          }
        });
  }

  private void ages(UUID tenant, Map<DekPurpose, Long> maxAge) {
    LocalDate today = LocalDate.now(clock);
    TenantTx.run(
        keyring,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT purpose, min(valid_month) FROM keyring.dek WHERE tenant_id = ?"
                      + " AND status = 'ACTIVE' AND purpose <> 'BLIND_INDEX' GROUP BY purpose")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                long d = ChronoUnit.DAYS.between(rs.getDate(2).toLocalDate(), today);
                maxAge.merge(DekPurpose.valueOf(rs.getString(1)), Math.max(0, d), Math::max);
              }
            }
          }
          return null;
        });
  }
}
