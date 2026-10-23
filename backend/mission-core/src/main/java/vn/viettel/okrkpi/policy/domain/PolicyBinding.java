package vn.viettel.okrkpi.policy.domain;

import java.util.UUID;

/**
 * Gắn phiên bản cho tổ hợp kỳ, luồng, khối, đơn vị ({@code policy.policy_binding}); {@code
 * customerProfileVersionId} là phiên bản hồ sơ tập khách hàng ghim cùng (CORE-05-12), rỗng với gắn
 * trước BS-03.
 */
public record PolicyBinding(
    UUID id,
    UUID periodId,
    String flow,
    UUID subjectGroupId,
    UUID orgUnitId,
    boolean frozen,
    UUID customerProfileVersionId) {}
