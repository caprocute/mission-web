package vn.viettel.okrkpi.oversight.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.BeginResult;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyExceptions;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyRecord;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyScope;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyStore;
import vn.viettel.okrkpi.shared.idempotency.JdbcIdempotencyStore;
import vn.viettel.okrkpi.shared.idempotency.RequestHasher;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Idempotency-Key cho lệnh ghi của MOD-07 trên {@code oversight.idempotency_key}, trong cùng giao
 * dịch {@code oversightTransactionManager} với lệnh; cùng quy trình {@code ConfigIdempotency} của
 * MOD-08 (wiki trang 14).
 */
public class OversightIdempotency {

  static final long RETRY_AFTER_SECONDS = 1;

  private final IdempotencyStore store;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public OversightIdempotency(
      DataSource oversightDataSource, PlatformTransactionManager oversightTm, ObjectMapper json) {
    this.store = new JdbcIdempotencyStore(new JdbcTemplate(oversightDataSource));
    this.tx = new TransactionTemplate(oversightTm);
    this.json = Objects.requireNonNull(json, "json");
  }

  /**
   * Chạy {@code work} đúng một lần cho mỗi khoá: lần gửi lại cùng nội dung phát lại phản hồi đã lưu
   * (kèm {@code Location} và {@code ETag}), khác nội dung trả 422, đang xử lý trả 409.
   */
  public <T> ResponseEntity<T> run(
      TenantId tenant,
      UUID actor,
      String key,
      String method,
      String path,
      Object requestBody,
      Class<T> responseType,
      java.util.function.ToLongFunction<T> rowVersion,
      Supplier<ResponseEntity<T>> work) {
    if (key == null || key.isEmpty()) {
      throw new IdempotencyExceptions.KeyRequired(false);
    }
    IdempotencyScope scope = new IdempotencyScope("oversight", tenant, ActorId.of(actor));
    byte[] hash = new RequestHasher(json).hash(method, path, requestBody);
    return tx.execute(
        s -> {
          BeginResult begin = store.begin(scope, key, hash);
          return switch (begin) {
            case BeginResult.Replay replay -> replay(replay.record(), responseType, rowVersion);
            case BeginResult.Mismatch m -> throw new IdempotencyExceptions.Mismatch();
            case BeginResult.InProgress p ->
                throw new IdempotencyExceptions.InProgress(RETRY_AFTER_SECONDS);
            case BeginResult.Started started -> {
              ResponseEntity<T> result = work.get();
              int status = result.getStatusCode().value();
              if (status < 200 || status > 299) {
                store.abandon(started);
              } else {
                store.complete(
                    started,
                    status,
                    write(result.getBody()),
                    result.getHeaders().getFirst(HttpHeaders.LOCATION));
              }
              yield result;
            }
          };
        });
  }

  private <T> ResponseEntity<T> replay(
      IdempotencyRecord record, Class<T> type, java.util.function.ToLongFunction<T> rowVersion) {
    T body = null;
    if (record.responseBody() != null) {
      try {
        body = json.readValue(record.responseBody(), type);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Phản hồi đã lưu của Idempotency-Key hỏng", e);
      }
    }
    ResponseEntity.BodyBuilder b =
        ResponseEntity.status(record.responseCode() == null ? 200 : record.responseCode());
    if (record.resourceRef() != null) {
      b.header(HttpHeaders.LOCATION, record.resourceRef());
    }
    if (body != null) {
      b.eTag(ETags.of(rowVersion.applyAsLong(body)));
    }
    return b.body(body);
  }

  private String write(Object body) {
    if (body == null) {
      return null;
    }
    try {
      return json.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được phản hồi để lưu khoá", e);
    }
  }
}
