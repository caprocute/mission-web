package vn.viettel.okrkpi.policy.domain;

import java.util.UUID;

/** Gắn phiên bản cho tổ hợp kỳ, luồng, khối, đơn vị ({@code policy.policy_binding}). */
public record PolicyBinding(
    UUID id, UUID periodId, String flow, UUID subjectGroupId, UUID orgUnitId, boolean frozen) {}
