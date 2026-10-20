package vn.viettel.okrkpi.shared.resilience;

import java.time.Duration;

/**
 * Bảng timeout mặc định của TL-01 "Timeout mặc định" cho các lời gọi mà Spring Boot không có thuộc
 * tính chuẩn (OPA, OpenBao, hệ thống ngoài, ký số). Lời gọi có thuộc tính chuẩn (REST nội bộ,
 * PostgreSQL, Valkey, Kafka) được đặt trong {@code timeouts-defaults.yml}. Nguyên tắc: timeout tầng
 * ngoài lớn hơn tổng timeout và retry của tầng trong.
 */
public final class PlatformTimeouts {

  /** Service tới service (REST nội bộ): kết nối. */
  public static final Duration INTERNAL_REST_CONNECT = Duration.ofMillis(500);

  /** Service tới service (REST nội bộ): đọc (bằng TimeLimiter mặc định). */
  public static final Duration INTERNAL_REST_READ = Duration.ofSeconds(3);

  /** PostgreSQL: lấy kết nối từ Hikari. */
  public static final Duration POSTGRES_CONNECTION = Duration.ofSeconds(2);

  /** PostgreSQL: câu lệnh và giao dịch. */
  public static final Duration POSTGRES_STATEMENT = Duration.ofSeconds(5);

  /** Valkey: kết nối. */
  public static final Duration VALKEY_CONNECT = Duration.ofMillis(200);

  /** Valkey: lệnh. */
  public static final Duration VALKEY_COMMAND = Duration.ofMillis(300);

  /** Kafka producer: kết nối. */
  public static final Duration KAFKA_CONNECT = Duration.ofSeconds(1);

  /** Kafka producer: {@code delivery.timeout.ms}. */
  public static final Duration KAFKA_DELIVERY = Duration.ofSeconds(30);

  /** OPA cùng pod: kết nối (lỗi thì từ chối). */
  public static final Duration OPA_CONNECT = Duration.ofMillis(50);

  /** OPA cùng pod: đọc. */
  public static final Duration OPA_READ = Duration.ofMillis(100);

  /** OpenBao: kết nối. */
  public static final Duration OPENBAO_CONNECT = Duration.ofMillis(500);

  /** OpenBao: đọc. */
  public static final Duration OPENBAO_READ = Duration.ofSeconds(2);

  /** Hệ thống ngoài (CBCCVC, LGSP, vOffice): kết nối; đọc 5 đến 10 s theo từng connector. */
  public static final Duration EXTERNAL_CONNECT = Duration.ofSeconds(2);

  /** Ký số từ xa: kết nối. */
  public static final Duration ESIGN_CONNECT = Duration.ofSeconds(2);

  /** Ký số từ xa: mỗi lời gọi. */
  public static final Duration ESIGN_CALL = Duration.ofSeconds(10);

  private PlatformTimeouts() {}
}
