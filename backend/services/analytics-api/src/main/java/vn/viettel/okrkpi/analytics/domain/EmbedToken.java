package vn.viettel.okrkpi.analytics.domain;

import java.time.Instant;

/**
 * Guest token ngắn hạn cho khung nhúng Superset ({@code createAnalyticsEmbedToken}, DV-15-05): phạm
 * vi {@code org_path} đã khoá cứng trong token (BR-16-01).
 */
public record EmbedToken(
    String token, String embedUrl, Instant expiresAt, String lockedOrgPathPrefix) {}
