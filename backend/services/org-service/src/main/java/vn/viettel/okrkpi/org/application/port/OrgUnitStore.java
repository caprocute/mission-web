package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Truy cập {@code org.org_unit}; mọi lời gọi nằm trong giao dịch đã đặt {@code app.tenant_id}. */
public interface OrgUnitStore {

  Optional<OrgUnitVersion> findAt(UUID id, TemporalQuery query);

  Map<UUID, OrgUnitVersion> findAllAt(Collection<UUID> ids, TemporalQuery query);

  /** Phiên bản là hiểu biết hiện tại, khoá dòng để ghi. */
  List<OrgUnitVersion> currentVersionsForUpdate(UUID id);

  /** Mọi phiên bản (kể cả đã bị thay thế) phục vụ lịch sử. */
  List<OrgUnitVersion> allVersions(UUID id);

  /** {@code row_version} lớn nhất của thực thể trong số phiên bản đã ghi nhận tới {@code recordedAsOf} (null: nay). */
  Optional<Integer> entityVersion(UUID id, OffsetDateTime recordedAsOf);

  /** Mã đơn vị đã được đơn vị khác dùng trong khoảng hiệu lực giao nhau (theo hiểu biết hiện tại). */
  boolean codeTakenByOther(String code, UUID exceptId, LocalDate from, LocalDate to);

  /** Khoá tư vấn trong giao dịch theo (tenant, mã) để kiểm mã duy nhất không bị chạy đua. */
  void lockCode(String code);

  /** Đóng và chèn theo kế hoạch; phiên bản chèn mang {@code rowVersion} và {@code changeOrderId} cho trước. */
  void apply(VersionPlan<OrgUnitVersion> plan, int rowVersion, UUID changeOrderId, Actor actor);

  List<UnitSearchRow> search(String text, TemporalQuery query, int limit);

  record UnitSearchRow(UUID id, String code, String name, String unitType) {
  }
}
