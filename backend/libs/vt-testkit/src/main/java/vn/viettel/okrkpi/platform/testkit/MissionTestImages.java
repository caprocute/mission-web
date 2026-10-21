package vn.viettel.okrkpi.platform.testkit;

/**
 * Phiên bản ảnh container chuẩn dùng chung giữa {@code infra/dev/docker-compose.yml} và các lớp
 * Testcontainers trong {@code vt-testkit} (TL-01 Phụ lục B).
 */
public final class MissionTestImages {

  /** PostgreSQL 17 cho dữ liệu chính, nhân sự, quy trình và sổ cái. */
  public static final String POSTGRES = "postgres:17";

  /** PgBouncer 1.24 chế độ gộp kết nối theo giao dịch ({@code transaction}). */
  public static final String PGBOUNCER = "edoburu/pgbouncer:v1.24.1-p0";

  /** Valkey 8 cho bộ nhớ đệm L2, khoá phân tán và danh sách thu hồi JWT {@code jti}. */
  public static final String VALKEY = "valkey/valkey:8-alpine";

  /** Kafka 3.9 chế độ KRaft (không dùng ZooKeeper). */
  public static final String KAFKA = "apache/kafka:3.9.1";

  /** Apicurio Registry 3.x lưu lược đồ sự kiện Avro/JSON. */
  public static final String APICURIO = "quay.io/apicurio/apicurio-registry:3.0.9";

  /** Debezium Connect 3.x đọc WAL + bảng {@code outbox_event}. */
  public static final String DEBEZIUM = "quay.io/debezium/connect:3.0.0.Final";

  /** Keycloak 26 cho xác thực OIDC/OAuth 2.1. */
  public static final String KEYCLOAK = "quay.io/keycloak/keycloak:26.4";

  /** OPA 1.x đánh giá chính sách phân quyền RBAC + ABAC Rego. */
  public static final String OPA = "openpolicyagent/opa:1.4.0";

  /** OpenBao 2.x quản lý khoá Transit KEK và bí mật KV v2. */
  public static final String OPENBAO = "quay.io/openbao/openbao:2.2.0";

  private MissionTestImages() {}
}
