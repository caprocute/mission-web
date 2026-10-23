package vn.viettel.okrkpi.appraisal.application.scoring.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bảng {@code appraisal.incident} (CORE-04-13, BS-03 DC-05) trên {@code appraisalDataSource}. */
public interface IncidentRepository {

  void insert(TenantId tenant, IncidentRow row);

  Optional<IncidentRow> find(TenantId tenant, UUID id);

  /**
   * Sự việc mới nhất trước (id UUIDv7 giảm dần), lọc theo người, kỳ và phạm vi đơn vị của người
   * gọi.
   *
   * @param unitScope đơn vị gốc của phạm vi: chỉ thấy sự việc ghi cho đơn vị đó và đơn vị con
   * @param beforeId id cuối của trang trước, {@code null} cho trang đầu
   */
  List<IncidentRow> list(
      TenantId tenant, UUID unitScope, UUID personId, UUID periodId, UUID beforeId, int limit);

  /** Đơn vị {@code unitId} có thuộc cây đơn vị dưới {@code rootUnitId} (bản sao tổ chức) không. */
  boolean withinUnit(TenantId tenant, UUID rootUnitId, UUID unitId);

  /**
   * Sự việc trong kỳ của nhiều người: ghi đúng kỳ, hoặc không ghi kỳ mà ngày kết luận nằm trong
   * {@code [from, to]}.
   */
  Map<UUID, List<IncidentRow>> forPersons(
      TenantId tenant, UUID periodId, LocalDate from, LocalDate to, Collection<UUID> personIds);

  /**
   * Hồ sơ đánh giá của người: đúng kỳ khi {@code periodId} có giá trị, ngược lại hồ sơ mới nhất.
   */
  Optional<PersonProfile> personProfile(TenantId tenant, UUID personId, UUID periodId);

  /** Hồ sơ đánh giá dùng để lấy đơn vị, luồng, khối khi ghi sự việc. */
  record PersonProfile(UUID periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {}

  /** Một dòng sự việc; tóm tắt ở dạng mã hoá. */
  record IncidentRow(
      UUID id,
      UUID personId,
      UUID periodId,
      UUID orgUnitId,
      String incidentType,
      LocalDate concludedOn,
      String documentNo,
      String documentRef,
      String impact,
      SealedText summary,
      UUID createdBy,
      Instant createdAt,
      int rowVersion) {

    public IncidentRow {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(personId, "personId");
      Objects.requireNonNull(incidentType, "incidentType");
      Objects.requireNonNull(concludedOn, "concludedOn");
    }
  }
}
