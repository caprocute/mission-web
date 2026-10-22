package vn.viettel.okrkpi.platform.keyrotation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Job bọc lại DEK khi KEK của tenant có phiên bản mới (xoay hằng năm hoặc khi nghi lộ KEK, SEC-12,
 * TL-01 "Xoay vòng khoá"): với mọi DEK còn {@code ACTIVE}, {@code DECRYPT_ONLY}, {@code RETIRED} mà
 * bản bọc còn ở phiên bản KEK cũ, gọi {@code transit/rewrap} theo lô và cập nhật {@code
 * wrapped_dek}, {@code kek_ref} trong cùng giao dịch của lô. Không đụng dữ liệu, không có DEK rõ
 * trong ứng dụng. DEK {@code DESTROYED} không được bọc lại. Chạy lại không đổi gì vì chỉ chọn bản
 * bọc cũ hơn phiên bản mới nhất. Sau khi job báo {@code remaining = 0} cho mọi tenant, quản trị
 * khoá mới được đặt {@code min_decryption_version} của KEK (runbook xoay-vong-khoa-du-lieu.md).
 */
public class KekRewrapJob {

  static final String JOB = "kek_boc_lai";

  /** Kết quả một lần chạy. */
  public record Result(int tenants, int rewrapped, int upToDate) {}

  private final JdbcTemplate keyring;
  private final TransitRewrapClient transit;
  private final TenantSource tenants;
  private final RotationReporter reporter;
  private final int batchSize;

  public KekRewrapJob(
      JdbcTemplate keyring,
      TransitRewrapClient transit,
      TenantSource tenants,
      RotationReporter reporter,
      int batchSize) {
    this.keyring = Objects.requireNonNull(keyring, "keyring");
    this.transit = Objects.requireNonNull(transit, "transit");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.reporter = Objects.requireNonNull(reporter, "reporter");
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("batchSize phải trong khoảng 1..1000");
    }
    this.batchSize = batchSize;
  }

  public Result run() {
    int rewrapped = 0;
    int upToDate = 0;
    List<UUID> all = tenants.tenants();
    for (UUID tenant : all) {
      List<DekRecord> rows = load(tenant);
      Map<String, List<DekRecord>> byKek = new LinkedHashMap<>();
      for (DekRecord r : rows) {
        byKek.computeIfAbsent(KekRef.parse(r.kekRef()).keyPath(), k -> new ArrayList<>()).add(r);
      }
      int tenantRewrapped = 0;
      Map<String, String> versions = new LinkedHashMap<>();
      for (List<DekRecord> group : byKek.values()) {
        KekRef kek = KekRef.parse(group.get(0).kekRef());
        int latest = transit.latestVersion(kek);
        versions.put(kek.keyPath(), "v" + latest);
        List<DekRecord> stale = new ArrayList<>();
        for (DekRecord r : group) {
          if (KekRef.wrappedVersion(r.wrappedDek()) < latest) {
            stale.add(r);
          } else {
            upToDate++;
          }
        }
        for (int i = 0; i < stale.size(); i += batchSize) {
          List<DekRecord> batch = stale.subList(i, Math.min(stale.size(), i + batchSize));
          tenantRewrapped += rewrapBatch(tenant, kek, latest, batch);
        }
      }
      rewrapped += tenantRewrapped;
      Map<String, String> attrs = new LinkedHashMap<>();
      attrs.put("so_dek_boc_lai", Integer.toString(tenantRewrapped));
      attrs.put("so_dek", Integer.toString(rows.size()));
      attrs.put("phien_ban_kek", String.join(",", versions.values()));
      reporter.audit("KEY_ROTATION_KEK_REWRAP", tenant, null, null, attrs);
    }
    reporter.items(JOB, "dek_boc_lai", rewrapped);
    return new Result(all.size(), rewrapped, upToDate);
  }

  private List<DekRecord> load(UUID tenant) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          List<DekRecord> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT "
                      + DekRecord.COLUMNS
                      + " FROM keyring.dek WHERE tenant_id = ? AND status <> 'DESTROYED' ORDER BY id")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(DekRecord.map(rs));
              }
            }
          }
          return out;
        });
  }

  private int rewrapBatch(UUID tenant, KekRef kek, int latest, List<DekRecord> batch) {
    List<byte[]> fresh = transit.rewrap(kek, batch.stream().map(DekRecord::wrappedDek).toList());
    String newRef = kek.withVersion(latest).toString();
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          int n = 0;
          // Chỉ ghi khi bản bọc chưa bị job khác đổi (so khớp bản bọc cũ): chạy song song an toàn.
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE keyring.dek SET wrapped_dek = ?, kek_ref = ? WHERE tenant_id = ? AND id = ?"
                      + " AND wrapped_dek = ? AND status <> 'DESTROYED'")) {
            for (int i = 0; i < batch.size(); i++) {
              DekRecord r = batch.get(i);
              ps.setBytes(1, fresh.get(i));
              ps.setString(2, newRef);
              ps.setObject(3, tenant);
              ps.setObject(4, r.id());
              ps.setBytes(5, r.wrappedDek());
              ps.addBatch();
            }
            for (int c : ps.executeBatch()) {
              n += Math.max(0, c);
            }
          }
          return n;
        });
  }
}
