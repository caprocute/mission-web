package vn.viettel.okrkpi.integ.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi ánh xạ mã ngoài theo yêu cầu của module nội bộ (DV-13-08): MOD-01 gắn chỉ số nguồn với KR
 * ({@code CHI_SO}), org-service gắn mã HRM với người ({@code NHAN_SU}) hay đơn vị, chức danh. Mã
 * ngoài đã gắn nơi khác thì trả xung đột, trừ khi bên gọi yêu cầu thay (đóng ánh xạ cũ từ ngày hiệu
 * lực mới).
 */
public class MappingCommands {

  private final Connectors connectors;
  private final MappingXrefService mappings;
  private final TransactionOperations tx;
  private final Clock clock;

  public MappingCommands(
      Connectors connectors, MappingXrefService mappings, TransactionOperations tx, Clock clock) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.mappings = Objects.requireNonNull(mappings, "mappings");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Trả {@code true} khi có thay đổi. */
  public boolean link(
      TenantId tenant,
      String connectorCode,
      EntityType type,
      String externalId,
      UUID internalId,
      LocalDate from,
      boolean replace) {
    Objects.requireNonNull(type, "type");
    if (internalId == null) {
      throw new IllegalArgumentException("Thiếu mã nội bộ");
    }
    LocalDate on = from == null ? LocalDate.now(clock) : from;
    Boolean changed =
        TenantScope.call(
            tenant,
            () ->
                tx.execute(
                    s -> {
                      Connector c =
                          connectors
                              .findByCode(tenant, connectorCode)
                              .orElseThrow(
                                  () ->
                                      new NoSuchElementException(
                                          "Tenant chưa cấu hình kết nối " + connectorCode));
                      Optional<UUID> current =
                          mappings.resolve(tenant, c.id(), type, externalId, on);
                      if (current.isPresent() && !current.get().equals(internalId) && !replace) {
                        throw new MappingConflictException("Mã ngoài đã gắn với mã nội bộ khác");
                      }
                      try {
                        return mappings.link(
                            tenant, c.id(), type, externalId, internalId, on, null);
                      } catch (IllegalStateException e) {
                        throw new MappingConflictException(e.getMessage());
                      }
                    }));
    return Boolean.TRUE.equals(changed);
  }
}
