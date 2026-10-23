package vn.viettel.okrkpi.integ.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Mapping;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tra hoặc tạo ánh xạ mã ngoài ↔ UUIDv7 nội bộ theo thời gian hiệu lực (cơ chế 8 baseline mục 8):
 * không bảng nội bộ nào ngoài {@code integ} giữ mã hệ ngoài. Gán lại cùng cặp mã là thao tác rỗng,
 * nên một vòng đồng bộ hai chiều không sinh ánh xạ hay sự kiện mới.
 */
public class MappingXrefService {

  private final MappingXrefs xrefs;

  public MappingXrefService(MappingXrefs xrefs) {
    this.xrefs = Objects.requireNonNull(xrefs, "xrefs");
  }

  public Optional<UUID> resolve(
      TenantId tenant, UUID connectorId, EntityType type, String externalId, LocalDate on) {
    return xrefs.byExternal(tenant, connectorId, type, externalId, on).map(Mapping::internalId);
  }

  /** Mã ngoài của mã nội bộ cho chiều ra. */
  public Optional<String> externalOf(
      TenantId tenant, UUID connectorId, EntityType type, UUID internalId, LocalDate on) {
    return xrefs.byInternal(tenant, connectorId, type, internalId, on).map(Mapping::externalId);
  }

  /** Mã nội bộ đang ánh xạ; chưa có thì cấp UUIDv7 mới, hiệu lực từ {@code on}. */
  public UUID resolveOrCreate(
      TenantId tenant,
      UUID connectorId,
      EntityType type,
      String externalId,
      LocalDate on,
      String note) {
    requireExternal(externalId);
    Optional<Mapping> current = xrefs.byExternal(tenant, connectorId, type, externalId, on);
    if (current.isPresent()) {
      return current.get().internalId();
    }
    UUID internal = Ids.newUuidV7();
    if (xrefs.insert(
        tenant, Ids.newUuidV7(), connectorId, type, externalId, internal, on, note(note))) {
      return internal;
    }
    return xrefs
        .byExternal(tenant, connectorId, type, externalId, on)
        .map(Mapping::internalId)
        .orElseThrow(
            () -> new IllegalStateException("Ánh xạ mã ngoài bị chặn nhưng không đọc lại được"));
  }

  /**
   * Gắn mã ngoài với mã nội bộ từ ngày {@code on}: đóng ánh xạ cũ trỏ nơi khác (hệ ngoài tái sử
   * dụng mã); giữ nguyên khi đã đúng. Trả {@code true} khi có thay đổi.
   */
  public boolean link(
      TenantId tenant,
      UUID connectorId,
      EntityType type,
      String externalId,
      UUID internalId,
      LocalDate on,
      String note) {
    requireExternal(externalId);
    Objects.requireNonNull(internalId, "internalId");
    Optional<Mapping> current = xrefs.byExternal(tenant, connectorId, type, externalId, on);
    if (current.isPresent()) {
      Mapping m = current.get();
      if (m.internalId().equals(internalId)) {
        return false;
      }
      if (m.validFrom().isBefore(on)) {
        xrefs.close(tenant, m.id(), on);
      } else {
        throw new IllegalStateException("Mã ngoài đã được gán trong cùng ngày hiệu lực");
      }
    }
    if (!xrefs.insert(
        tenant, Ids.newUuidV7(), connectorId, type, externalId, internalId, on, note(note))) {
      throw new IllegalStateException("Ánh xạ chồng thời gian hiệu lực");
    }
    return true;
  }

  private static void requireExternal(String externalId) {
    if (externalId == null || externalId.isBlank() || externalId.length() > 128) {
      throw new IllegalArgumentException("Mã ngoài phải có từ 1 đến 128 ký tự");
    }
  }

  private static String note(String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    return note.length() > 255 ? note.substring(0, 255) : note;
  }
}
