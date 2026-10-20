package vn.viettel.okrkpi.org.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.IdempotencyMismatchException;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * Khoá chống ghi trùng theo TL-03 {@code idempotency_key}: chèn trong cùng giao dịch với thao tác nghiệp vụ, nên
 * thao tác lỗi thì khoá cũng không còn và được gửi lại. Yêu cầu song song cùng khoá chờ khoá chính rồi đọc kết quả
 * đã lưu. Thay bằng bộ lọc của {@code vt-platform-starter} khi có.
 */
@Component
public class JdbcIdempotencyStore implements IdempotencyPort {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcIdempotencyStore(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public <R> Optional<R> begin(Actor actor, IdempotencyRequest request, Class<R> resultType) {
    Optional<Optional<R>> earlier = lookup(actor, request, resultType, "created_day >= current_date - 2");
    if (earlier.isPresent()) {
      return earlier.get();
    }
    int inserted = jdbc.sql("INSERT INTO org.idempotency_key (tenant_id, actor_id, idem_key, created_day, "
            + "request_hash, status) VALUES (:tenant, :actor, :key, current_date, :hash, 'DANG_XU_LY') "
            + "ON CONFLICT DO NOTHING")
        .param("tenant", Sql.tenant()).param("actor", actor.actorId()).param("key", request.key())
        .param("hash", request.requestHash())
        .update();
    if (inserted == 1) {
      return Optional.empty();
    }
    return lookup(actor, request, resultType, "created_day = current_date")
        .orElseThrow(() -> new VersionConflictException(null, null));
  }

  private <R> Optional<Optional<R>> lookup(Actor actor, IdempotencyRequest request, Class<R> type, String dayFilter) {
    return jdbc.sql("SELECT request_hash, status, response_body::text AS body FROM org.idempotency_key "
            + "WHERE tenant_id = :tenant AND actor_id = :actor AND idem_key = :key AND " + dayFilter
            + " ORDER BY created_day DESC LIMIT 1")
        .param("tenant", Sql.tenant()).param("actor", actor.actorId()).param("key", request.key())
        .query((rs, n) -> {
          if (!Arrays.equals(rs.getBytes("request_hash"), request.requestHash())) {
            throw new IdempotencyMismatchException();
          }
          if (!"XONG".equals(rs.getString("status")) || rs.getString("body") == null) {
            // Yêu cầu trước cùng khoá chưa xong trong giao dịch khác.
            throw new VersionConflictException(null, null);
          }
          return Optional.of(read(rs.getString("body"), type));
        })
        .optional();
  }

  @Override
  public void complete(Actor actor, IdempotencyRequest request, int status, Object result, String resourceRef) {
    try {
      jdbc.sql("UPDATE org.idempotency_key SET status = 'XONG', response_code = :code, "
              + "response_body = CAST(:body AS jsonb), resource_ref = :ref WHERE tenant_id = :tenant "
              + "AND actor_id = :actor AND idem_key = :key AND created_day = current_date")
          .param("code", status).param("body", json.writeValueAsString(result)).param("ref", resourceRef)
          .param("tenant", Sql.tenant()).param("actor", actor.actorId()).param("key", request.key())
          .update();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <R> R read(String body, Class<R> type) {
    try {
      return json.readValue(body, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
