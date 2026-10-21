package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.SubjectGroupRecord;

/** Truy cập {@code org.subject_group} (9 khối PL 2A). */
public interface SubjectGroupStore {

  List<SubjectGroupRecord> list(LocalDate asOf);

  boolean isValid(UUID id, LocalDate day);

  Map<UUID, String> names(Collection<UUID> ids);
}
