package vn.viettel.okrkpi.platform.keyrotation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện kiểm toán {@code sys.audit.v1} và chỉ số của job xoay vòng (OPS-20). Chỉ ghi loại xoay,
 * tenant, mã DEK và số đếm; không bao giờ ghi DEK, bản bọc hay bản rõ.
 *
 * <ul>
 *   <li>{@code vt_key_age_days{loai="dek",muc_dich}}: tuổi (ngày) lớn nhất của DEK {@code ACTIVE}
 *       theo mục đích, tính từ tháng hiệu lực; cảnh báo OPS-20 khi quá hạn 7 ngày (quá 38 ngày);
 *   <li>{@code vt_key_rotation_items_total{job,ket_qua}}: số DEK hoặc số dòng đã xử lý;
 *   <li>{@code vt_key_rotation_remaining{job}}: số dòng còn chờ mã hoá lại của lần chạy gần nhất.
 * </ul>
 */
public class RotationReporter {

  static final String AGE = "vt.key.age.days";
  static final String ITEMS = "vt.key.rotation.items";
  static final String REMAINING = "vt.key.rotation.remaining";

  private final MeterRegistry registry;
  private final AuditLogger audit;
  private final ActorId jobActor;
  private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

  /**
   * @param jobActor tài khoản dịch vụ của job (mã người thực hiện trong sự kiện kiểm toán)
   */
  public RotationReporter(MeterRegistry registry, AuditLogger audit, ActorId jobActor) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.jobActor = Objects.requireNonNull(jobActor, "jobActor");
  }

  void keyAge(String purpose, long days) {
    gauge(AGE, "loai", "dek", "muc_dich", purpose).set(days);
  }

  void remaining(String job, long rows) {
    gauge(REMAINING, "job", job).set(rows);
  }

  void items(String job, String result, long n) {
    if (n > 0) {
      Counter.builder(ITEMS).tag("job", job).tag("ket_qua", result).register(registry).increment(n);
    }
  }

  void audit(String action, UUID tenant, ActorId actor, UUID dekId, Map<String, String> attrs) {
    AuditEvent.Builder b =
        AuditEvent.builder(AuditEventType.ADMIN_ACTION, actor == null ? jobActor : actor, action)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(TenantId.of(tenant))
            .object("keyring.dek", dekId == null ? null : dekId.toString());
    attrs.forEach(b::attribute);
    audit.record(b.build());
  }

  private AtomicLong gauge(String name, String... tags) {
    return gauges.computeIfAbsent(
        name + String.join(",", tags),
        k -> {
          AtomicLong v = new AtomicLong();
          Gauge.builder(name, v, AtomicLong::get).tags(tags).register(registry);
          return v;
        });
  }
}
