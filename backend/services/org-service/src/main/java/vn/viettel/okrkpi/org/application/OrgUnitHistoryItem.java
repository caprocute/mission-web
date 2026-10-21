package vn.viettel.okrkpi.org.application;

import java.time.OffsetDateTime;
import java.util.List;
import vn.viettel.okrkpi.org.domain.OrgChangeType;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;

/** Một phiên bản cho vùng 4 "Lịch sử thay đổi" (BR-01-06). */
public record OrgUnitHistoryItem(
    OrgUnitVersion version,
    String parentName,
    String subjectGroupName,
    String recordedByName,
    OffsetDateTime recordedTo,
    OrgChangeType changeType,
    ChangeOrderRecord changeOrder,
    List<String> changedFields) {
}
