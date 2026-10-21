package vn.viettel.okrkpi.replica.domain;

import java.util.UUID;

/** Trạng thái nhân sự mang trong EVT-16 (không có trường C2). */
public record PersonSnapshot(
    UUID id, String fullName, boolean partyMember, String employmentStatus, long sourceVersion) {}
