package vn.viettel.okrkpi.integ.application;

import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ngữ cảnh translator: tenant, kết nối nguồn, ngày xử lý và dịch vụ ánh xạ mã. */
public record TranslationContext(
    TenantId tenant, Connector connector, LocalDate today, MappingXrefService mappings) {

  /** Mã nội bộ của mã ngoài, tạo UUIDv7 mới khi chưa có ánh xạ. */
  public UUID internalId(EntityType type, String externalId) {
    return mappings.resolveOrCreate(tenant, connector.id(), type, externalId, today, null);
  }
}
