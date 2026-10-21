package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-07 · Bảng {@code org.sync_discrepancy}. */
public interface DiscrepancyStore {

  /** Một dòng lệch; giá trị hai phía là bản mã C2. */
  record Row(UUID id, UUID personId, String field, String connector, UUID dekId, byte[] sourceEnc,
      byte[] currentEnc, String severity, String suggestedAction, String resolution, UUID resolvedBy,
      OffsetDateTime resolvedAt, UUID syncRunId, String externalId, LocalDate effectiveFrom, String matchedBy,
      UUID assigneeId, String note, byte[] fingerprint, int rowVersion) {
  }

  /** Dòng gần nhất còn mở hoặc đang giữ nội bộ của cùng nguồn, mã ngoài, trường. */
  Optional<Row> findLatestActive(String connector, String externalId, String field);

  void insert(Row row, Actor actor);

  /** Cập nhật giá trị của dòng còn mở khi lần đồng bộ sau thấy lại cùng lệch. */
  void refresh(Row row, Actor actor);

  List<Row> findForUpdate(Collection<UUID> ids);

  void resolve(UUID id, String resolution, String note, Actor actor);

  void assign(UUID id, UUID assigneeId, String note, Actor actor);

  void mapPerson(UUID id, UUID personId, Actor actor);

  List<Row> page(String field, String severity, boolean unresolved, UUID after, int limit);

  Map<String, Long> countBySeverity(String field, boolean unresolved);

  Map<String, Long> countByField(String severity, boolean unresolved);

  long countOpenHigh();

  Map<UUID, String> unitNames(Collection<UUID> ids);

  Map<UUID, String> jobTitleNames(Collection<UUID> ids);
}
