package vn.viettel.okrkpi.platform.retention;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;

/**
 * Xoá bằng mật mã (TL-03 "Xoá và giữ pháp lý", chương 9; DB-RET-03): với lớp {@code THUC_HIEN},
 * {@code VET_TINH}, DEK của tháng đã hết tổng thời hạn (tính từ cuối tháng của DEK) bị huỷ: bản bọc
 * bị xoá, trạng thái {@code DESTROYED}, nên mọi bản mã C2 của tháng đó, kể cả trong Parquet lưu trữ
 * và bản sao lưu, không còn mở được. DEK đang được dữ liệu có {@code legal_hold} dùng thì bỏ qua,
 * báo trong kết quả, và Object Lock của các tệp liên quan được gia hạn. Kết nối bằng {@code
 * svc_keyring} theo từng tenant. Bộ đệm DEK trong pod còn giữ khoá tối đa 15 phút sau khi huỷ.
 */
public final class CryptoEraseJob {

  private static final Logger log = LoggerFactory.getLogger(CryptoEraseJob.class);
  private static final List<RetentionClass> CLASSES =
      List.of(RetentionClass.THUC_HIEN, RetentionClass.VET_TINH);

  public record Held(UUID tenant, UUID dekId, int objectsExtended) {}

  public record Result(List<UUID> destroyed, List<Held> held) {}

  private final JdbcTemplate keyring;
  private final RetentionRules rules;
  private final TenantSource tenants;
  private final ClassMap classes;
  private final LegalHoldSource holds;
  private final ArchiveStore store;
  private final Period holdExtension;
  private final RetentionAudit audit;
  private final Clock clock;

  public CryptoEraseJob(
      JdbcTemplate keyring,
      RetentionRules rules,
      TenantSource tenants,
      ClassMap classes,
      LegalHoldSource holds,
      ArchiveStore store,
      Period holdExtension,
      RetentionAudit audit,
      Clock clock) {
    this.keyring = keyring;
    this.rules = rules;
    this.tenants = tenants;
    this.classes = classes;
    this.holds = holds;
    this.store = store;
    this.holdExtension = holdExtension;
    this.audit = audit;
    this.clock = clock;
  }

  public Result run() {
    LocalDate today = LocalDate.now(clock);
    List<UUID> destroyed = new ArrayList<>();
    List<Held> held = new ArrayList<>();
    for (UUID tenant : tenants.tenants()) {
      Map<RetentionClass, RetentionClass.Rule> r = rules.forTenant(tenant);
      for (RetentionClass rc : CLASSES) {
        List<DekPurpose> purposes = classes.purposes(rc);
        if (purposes.isEmpty()) {
          continue;
        }
        Period total = r.get(rc).total();
        for (UUID dek : expired(tenant, purposes, total, today)) {
          List<LegalHoldSource.HeldObject> objects = holds.heldObjects(tenant, dek);
          if (!objects.isEmpty() || holds.heldElsewhere(tenant, dek)) {
            int extended = 0;
            for (LegalHoldSource.HeldObject o : objects) {
              if (store.extendRetention(
                  o.bucket(), o.key(), o.versionId(), today.plus(holdExtension))) {
                extended++;
              }
            }
            held.add(new Held(tenant, dek, extended));
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("lop_luu_giu", rc.name());
            attrs.put("ket_qua", "BO_QUA_GIU_PHAP_LY");
            attrs.put("so_tep_gia_han", Integer.toString(extended));
            audit.record(
                "RETENTION_CRYPTO_ERASE", tenant, null, "keyring.dek", dek.toString(), attrs);
            log.warn("Bỏ qua huỷ DEK {} của tenant {} vì đang giữ pháp lý", dek, tenant);
            continue;
          }
          if (destroy(tenant, dek)) {
            destroyed.add(dek);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("lop_luu_giu", rc.name());
            attrs.put("ket_qua", "DA_HUY");
            attrs.put("tong_thoi_han", total.toString());
            audit.record(
                "RETENTION_CRYPTO_ERASE", tenant, null, "keyring.dek", dek.toString(), attrs);
          }
        }
      }
    }
    return new Result(destroyed, held);
  }

  private List<UUID> expired(
      UUID tenant, List<DekPurpose> purposes, Period total, LocalDate today) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          List<UUID> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT id, purpose, valid_month FROM keyring.dek WHERE tenant_id = ? AND status"
                      + " <> 'DESTROYED' ORDER BY valid_month, purpose")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                DekPurpose p = DekPurpose.valueOf(rs.getString(2));
                LocalDate month = rs.getObject(3, LocalDate.class);
                if (purposes.contains(p) && !month.plusMonths(1).plus(total).isAfter(today)) {
                  out.add(rs.getObject(1, UUID.class));
                }
              }
            }
          }
          return out;
        });
  }

  private boolean destroy(UUID tenant, UUID dek) {
    return TenantTx.run(
        keyring,
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE keyring.dek SET status = 'DESTROYED', wrapped_dek = '\\x'::bytea,"
                      + " destroyed_at = now() WHERE tenant_id = ? AND id = ? AND status <>"
                      + " 'DESTROYED'")) {
            ps.setObject(1, tenant);
            ps.setObject(2, dek);
            return ps.executeUpdate() == 1;
          }
        });
  }
}
