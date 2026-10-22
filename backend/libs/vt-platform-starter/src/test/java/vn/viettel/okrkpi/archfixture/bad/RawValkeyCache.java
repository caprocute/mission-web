package vn.viettel.okrkpi.archfixture.bad;

import io.lettuce.core.api.StatefulRedisConnection;

/** Mẫu sai cho NEN-035: tự ghép khoá và gọi Lettuce trực tiếp, bỏ qua tiền tố tenant. */
public class RawValkeyCache {

  private final StatefulRedisConnection<String, String> connection;

  public RawValkeyCache(StatefulRedisConnection<String, String> connection) {
    this.connection = connection;
  }

  public String get(String id) {
    return connection.sync().get("task:card:" + id);
  }
}
