package vn.viettel.okrkpi.analytics.domain;

/** Cột kết quả ({@code AnalyticsColumn}): {@code type} thuộc string, number, count, time. */
public record Column(String name, String title, String type, String unit) {}
