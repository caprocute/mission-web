package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phía ClickHouse của R5: đếm dòng còn sống và tổng kiểm mã theo (tenant, {@code period_bucket})
 * của một bảng sự kiện, qua giao diện HTTP của ClickHouse (không thêm trình điều khiển JDBC). Tổng
 * kiểm là tổng 32 bit đầu của MD5 mã dòng dạng chữ thường, cùng công thức với phía PostgreSQL ở
 * {@link AnalyticsReconciler}.
 */
public class ClickHouseAggregates {

  /** Khoá nhóm. */
  public record Group(UUID tenant, int periodBucket) {}

  /** Số dòng và tổng kiểm của một nhóm. */
  public record Agg(long rows, long checksum) {}

  private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*");

  private final HttpClient http;
  private final URI base;
  private final String database;
  private final String authorization;

  public ClickHouseAggregates(
      HttpClient http, URI base, String database, String username, String password) {
    this.http = Objects.requireNonNull(http, "http");
    this.base = Objects.requireNonNull(base, "base");
    if (database == null || !NAME.matcher(database).matches()) {
      throw new IllegalArgumentException("Tên CSDL ClickHouse không hợp lệ: " + database);
    }
    this.database = database;
    this.authorization =
        username == null || username.isBlank()
            ? null
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
  }

  /** Tổng hợp mọi tenant của bảng {@code fact} (dùng {@code FINAL}, bỏ dòng đã xoá). */
  public Map<Group, Agg> aggregate(String fact) {
    if (!NAME.matcher(fact).matches()) {
      throw new IllegalArgumentException("Tên bảng không hợp lệ: " + fact);
    }
    String sql =
        "SELECT tenant_id, period_bucket, count(),"
            + " sum(toUInt64(reinterpretAsUInt32(reverse(substring(MD5(lower(toString(id))), 1, 4)))))"
            + " FROM "
            + database
            + "."
            + fact
            + " FINAL WHERE _is_deleted = 0 GROUP BY tenant_id, period_bucket FORMAT TabSeparated";
    HttpRequest.Builder req =
        HttpRequest.newBuilder(
                base.resolve("/?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8)))
            .timeout(Duration.ofMinutes(2))
            .GET();
    if (authorization != null) {
      req.header("Authorization", authorization);
    }
    HttpResponse<String> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Không gọi được ClickHouse", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi gọi ClickHouse", e);
    }
    if (res.statusCode() != 200) {
      String body = res.body() == null ? "" : res.body();
      throw new IllegalStateException(
          "ClickHouse trả "
              + res.statusCode()
              + " cho bảng "
              + fact
              + ": "
              + body.substring(0, Math.min(300, body.length())));
    }
    Map<Group, Agg> out = new HashMap<>();
    for (String line : res.body().split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      String[] c = line.split("\t");
      out.put(
          new Group(UUID.fromString(c[0]), Integer.parseInt(c[1])),
          new Agg(Long.parseLong(c[2]), Long.parseUnsignedLong(c[3])));
    }
    return out;
  }
}
