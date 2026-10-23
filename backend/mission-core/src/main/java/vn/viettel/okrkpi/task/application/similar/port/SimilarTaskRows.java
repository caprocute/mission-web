package vn.viettel.okrkpi.task.application.similar.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc {@code task.task} để so khớp gần giống; mọi truy vấn lọc theo tenant. */
public interface SimilarTaskRows {

  /** Một nhiệm vụ ứng viên. */
  record Row(
      UUID id,
      UUID periodId,
      UUID orgUnitId,
      String code,
      String title,
      String status,
      boolean restricted,
      LocalDate dueDate,
      BigDecimal progressPct,
      byte[] fingerprint) {}

  /** Nhiệm vụ cần so: kỳ, đơn vị, tên, sản phẩm, chủ trì và dấu vân tay đã lưu. */
  record Probe(
      UUID taskId,
      UUID periodId,
      UUID unitId,
      String title,
      String productName,
      byte[] fingerprint) {}

  Optional<Probe> probe(TenantId tenant, UUID taskId);

  List<Row> byIds(TenantId tenant, UUID periodId, Collection<UUID> ids);

  List<Row> byFingerprint(TenantId tenant, UUID periodId, byte[] fingerprint);

  /** Nhiệm vụ cùng kỳ, đơn vị (dùng khi không cấu hình SVC-16), mới nhất trước. */
  List<Row> inUnit(TenantId tenant, UUID periodId, UUID unitId, int limit);
}
