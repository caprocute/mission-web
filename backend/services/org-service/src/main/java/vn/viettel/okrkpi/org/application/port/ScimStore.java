package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-06 · {@code org.scim_resource} (ánh xạ tài nguyên SCIM) và {@code org.scim_sync_log} (nhật ký lệnh). */
public interface ScimStore {

  /** Một tài nguyên SCIM của một nguồn; {@code deleted} là nguồn đã gửi DELETE. */
  record Resource(UUID id, String source, String type, String externalId, UUID internalId, String userName,
      String displayName, String titleCode, boolean active, boolean deleted, OffsetDateTime created,
      OffsetDateTime lastModified, int version) {
  }

  /** Một dòng nhật ký lệnh SCIM. */
  record LogEntry(UUID id, String source, String operation, String type, String externalId, UUID internalId,
      byte[] requestHash, String status, String errorDetail) {
  }

  Optional<Resource> find(String source, String type, UUID id);

  Optional<Resource> findByExternalId(String source, String type, String externalId);

  /**
   * Trang tài nguyên chưa xoá của nguồn, lọc tuỳ chọn theo một thuộc tính ({@code userName}, {@code externalId},
   * {@code displayName}) bằng so khớp chính xác.
   */
  List<Resource> list(String source, String type, String attribute, String value, int offset, int limit);

  int count(String source, String type, String attribute, String value);

  void insert(Resource resource, Actor actor);

  /** Ghi lại thuộc tính, {@code active}, cờ xoá; tăng {@code row_version}. Trả phiên bản mới. */
  int update(Resource resource, Actor actor);

  /** Mã người dùng SCIM (cùng nguồn) đang có phân công chính tại đơn vị theo nhánh, tại ngày {@code day}. */
  List<UUID> members(String source, UUID unitId, String branch, LocalDate day);

  Optional<UUID> jobTitleIdByCode(String code);

  /** Băm yêu cầu của lệnh gần nhất trên cùng tài nguyên khi lệnh đó thành công; rỗng nếu chưa có hay đã lỗi. */
  Optional<byte[]> lastSuccessfulHash(String source, String type, String externalId);

  void log(LogEntry entry, Actor actor);
}
