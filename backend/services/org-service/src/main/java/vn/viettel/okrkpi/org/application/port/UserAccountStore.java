package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-09 · Đọc và ghi {@code org.user_account}. */
public interface UserAccountStore {

  /** Dòng tài khoản; {@code rowVersion} là khoá lạc quan của chính dòng này. */
  record AccountRow(UUID personId, String username, String status, OffsetDateTime lockedAt, String lockReason,
      OffsetDateTime passwordResetAt, String source, OffsetDateTime createdAt, int rowVersion) {
  }

  /** Cán bộ cùng đơn vị, chức danh của phân công chính (hoặc phân công còn hiệu lực đầu tiên) tại {@code day}. */
  record PersonRow(UUID personId, String fullName, UUID unitId, String unitName, String jobTitleName,
      int concurrentCount) {
  }

  /** Chức danh của danh mục (tenant và danh mục quốc gia dùng chung). */
  record JobTitleRow(UUID id, String code, String name, Integer level) {
  }

  /** Cán bộ có phân công tại một trong {@code unitIds} ({@code null} là toàn tenant) ở ngày {@code day}. */
  List<PersonRow> people(Collection<UUID> unitIds, LocalDate day);

  Optional<PersonRow> person(UUID personId, LocalDate day);

  List<JobTitleRow> jobTitles(LocalDate day);

  Optional<AccountRow> find(UUID personId);

  List<AccountRow> findAll(Collection<UUID> personIds);

  /** Ghi mới; dòng đã có (ghi bù đồng thời) thì bỏ qua. */
  void insert(UUID personId, String username, String status, String source, Actor actor);

  /** Đổi trạng thái; trả false khi {@code expectedVersion} lệch. */
  boolean setStatus(UUID personId, String status, String lockReason, int expectedVersion, Actor actor);

  void markPasswordReset(UUID personId, Actor actor);

  /** Tăng phiên bản khi chỉ hồ sơ đổi (họ tên, thư điện tử); trả false khi {@code expectedVersion} lệch. */
  boolean touch(UUID personId, int expectedVersion, Actor actor);
}
