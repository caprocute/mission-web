package vn.viettel.okrkpi.replica.api;

import java.time.LocalDate;
import java.util.UUID;

/** Phân công hiệu lực đọc từ {@code org_replica.assignment_v}; {@code validTo} rỗng là vô hạn. */
public record AssignmentView(
    UUID id,
    UUID personId,
    UUID orgUnitId,
    UUID positionId,
    UUID jobTitleId,
    String kind,
    String branch,
    boolean head,
    LocalDate validFrom,
    LocalDate validTo) {}
