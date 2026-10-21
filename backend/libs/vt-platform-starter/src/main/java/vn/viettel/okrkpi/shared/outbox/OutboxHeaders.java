package vn.viettel.okrkpi.shared.outbox;

/**
 * Tên khoá trong cột {@code headers} (jsonb) của {@code outbox_event}; {@code
 * OutboxHeadersTransform} chuyển từng khoá thành một tiêu đề Kafka cùng tên. Tên viết thường theo
 * quy ước thuộc tính mở rộng CloudEvents ở {@code contracts/events/README.md} (TL-01 "Phong bì sự
 * kiện"); {@link #CE_TYPE} và {@link #APICURIO_GLOBAL_ID} theo cấu hình connector mẫu của TL-03.
 * Consumer NEN-012 đọc các tiêu đề này.
 */
public final class OutboxHeaders {

  /** W3C Trace Context của giao dịch phát sự kiện. */
  public static final String TRACEPARENT = "traceparent";

  /** Người thực hiện. */
  public static final String ACTOR_ID = "actorid";

  /** Tenant. */
  public static final String TENANT_ID = "tenantid";

  /** Phân loại cao nhất của dữ liệu trong sự kiện (C0, C1). */
  public static final String CLASSIFICATION = "classification";

  /** Phiên bản Policy Pack, chỉ có khi sự kiện gắn với một Policy Pack. */
  public static final String POLICY_VERSION = "policyversion";

  /** {@code aggregate_version} dạng chuỗi số thập phân. */
  public static final String AGGREGATE_VERSION = "aggregateversion";

  /** Tên nghiệp vụ của sự kiện (cột {@code event_type}), như {@code event_type:header:ce_type}. */
  public static final String CE_TYPE = "ce_type";

  /** Thời điểm xảy ra, ISO-8601 UTC (TL-03 cột {@code headers}: {@code occurredAt}). */
  public static final String OCCURRED_AT = "occurredat";

  /**
   * Global id lược đồ Apicurio; trong jsonb là số, trên Kafka là 8 byte big-endian đúng định dạng
   * mà {@code DefaultHeadersHandler} của serde Apicurio đọc.
   */
  public static final String APICURIO_GLOBAL_ID = "apicurio.value.globalId";

  /** Tiêu đề mã sự kiện do Debezium EventRouter đặt từ cột {@code id}. */
  public static final String EVENT_ID = "id";

  private OutboxHeaders() {}
}
