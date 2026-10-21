package vn.viettel.okrkpi.replica.infrastructure.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bọc {@link JdbcTemplate} trên {@code orgReplicaDataSource}. Không đăng ký thẳng {@code
 * JdbcTemplate} làm bean để khỏi chặn {@code JdbcTemplateAutoConfiguration} (Spring Modulith ghi
 * {@code modulith.event_publication} qua JdbcTemplate mặc định của DataSource chính).
 */
public record OrgReplicaJdbc(JdbcTemplate template) {
  public OrgReplicaJdbc {
    Objects.requireNonNull(template, "template");
  }
}
