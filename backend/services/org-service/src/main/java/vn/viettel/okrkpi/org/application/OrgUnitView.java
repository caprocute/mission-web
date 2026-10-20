package vn.viettel.okrkpi.org.application;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;

/** Đơn vị tại thời điểm hỏi kèm thông tin hiển thị của vùng 3 SCR-0103. */
public record OrgUnitView(
    OrgUnitVersion version,
    UUID parentId,
    String parentName,
    String subjectGroupName,
    List<UUID> predecessorIds,
    String recordedByName,
    int assignmentCount,
    int rowVersion) {
}
