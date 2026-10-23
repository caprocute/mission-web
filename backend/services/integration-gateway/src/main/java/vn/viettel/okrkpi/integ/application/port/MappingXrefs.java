package vn.viettel.okrkpi.integ.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ánh xạ mã ngoài với mã nội bộ có hiệu lực theo thời gian ({@code integ.mapping_xref}). */
public interface MappingXrefs {

  record Mapping(UUID id, String externalId, UUID internalId, LocalDate validFrom) {}

  /** Ánh xạ của mã ngoài còn hiệu lực ngày {@code on}. */
  Optional<Mapping> byExternal(
      TenantId tenant, UUID connectorId, EntityType type, String externalId, LocalDate on);

  /** Ánh xạ của mã nội bộ còn hiệu lực ngày {@code on} (chiều ra). */
  Optional<Mapping> byInternal(
      TenantId tenant, UUID connectorId, EntityType type, UUID internalId, LocalDate on);

  /**
   * Thêm ánh xạ hiệu lực từ {@code from}; {@code false} khi ràng buộc EXCLUDE chặn vì đã có ánh xạ
   * chồng thời gian (do luồng khác vừa ghi).
   */
  boolean insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      EntityType type,
      String externalId,
      UUID internalId,
      LocalDate from,
      String note);

  /** Đóng ánh xạ ở ngày {@code to} (không gồm); {@code false} khi không còn mở. */
  boolean close(TenantId tenant, UUID mappingId, LocalDate to);
}
