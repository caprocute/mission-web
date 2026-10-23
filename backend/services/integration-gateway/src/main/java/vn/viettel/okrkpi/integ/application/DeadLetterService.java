package vn.viettel.okrkpi.integ.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.DeadLetters.Letter;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Use case thư chết cho quản trị tenant (TL-01 "Lớp chống ô nhiễm"): ghi khi hết lượt thử, liệt kê,
 * chạy lại, đánh dấu đã xử lý ({@code resolved_by/at}). Hợp đồng chỉ có {@code POST
 * /api/v1/org/sync-runs/{id}/retry} do SVC-04 sở hữu nên SVC-04 gọi use case này qua cổng nội bộ
 * (suy luận, wiki trang 14).
 */
public class DeadLetterService {

  public static final String INBOUND = "VAO";
  public static final String OUTBOUND = "RA";
  public static final int MAX_PAGE = 200;

  private final DeadLetters letters;
  private final InboundPipeline inbound;
  private final OutboundPipeline outbound;
  private final TransactionOperations tx;
  private final Clock clock;

  public DeadLetterService(
      DeadLetters letters,
      InboundPipeline inbound,
      OutboundPipeline outbound,
      TransactionOperations tx,
      Clock clock) {
    this.letters = Objects.requireNonNull(letters, "letters");
    this.inbound = Objects.requireNonNull(inbound, "inbound");
    this.outbound = Objects.requireNonNull(outbound, "outbound");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Ghi thư chết của thông điệp {@code refMessageId}; chỉ lưu loại lỗi, không lưu nội dung. */
  public UUID record(
      TenantId tenant, UUID connectorId, String direction, UUID refMessageId, Throwable error) {
    if (!INBOUND.equals(direction) && !OUTBOUND.equals(direction)) {
      throw new IllegalArgumentException("Chiều thư chết phải là VAO hoặc RA");
    }
    UUID id = Ids.newUuidV7();
    TenantScope.call(
        tenant,
        () -> {
          tx.executeWithoutResult(
              s ->
                  letters.insert(
                      tenant,
                      id,
                      connectorId,
                      direction,
                      refMessageId,
                      ErrorText.code(error),
                      ErrorText.detail(error)));
          return null;
        });
    return id;
  }

  /** Trang thư chết theo thời gian ghi; {@code connectorId} rỗng là mọi kết nối của tenant. */
  public List<Letter> list(
      TenantId tenant,
      UUID connectorId,
      boolean openOnly,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    if (limit < 1 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit phải từ 1 đến 200");
    }
    if ((afterCreatedAt == null) != (afterId == null)) {
      throw new IllegalArgumentException("Con trỏ phải có đủ thời điểm và mã");
    }
    return TenantScope.call(
        tenant,
        () ->
            tx.execute(
                s -> letters.page(tenant, connectorId, openOnly, afterCreatedAt, afterId, limit)));
  }

  /** Chạy lại thông điệp rồi đánh dấu đã xử lý; lỗi khi chạy lại để thư còn mở. */
  public void replay(TenantId tenant, UUID id, UUID actor) {
    Letter letter = open(tenant, id);
    if (INBOUND.equals(letter.direction())) {
      inbound.reprocess(tenant, letter.refMessageId());
    } else {
      TenantScope.call(
          tenant,
          () -> {
            tx.executeWithoutResult(s -> outbound.reset(tenant, letter.refMessageId()));
            return null;
          });
    }
    resolve(tenant, id, actor);
  }

  /** Đánh dấu đã xử lý tay, không chạy lại. */
  public void resolve(TenantId tenant, UUID id, UUID actor) {
    Objects.requireNonNull(actor, "actor");
    boolean done =
        TenantScope.call(
            tenant, () -> tx.execute(s -> letters.resolve(tenant, id, actor, clock.instant())));
    if (!done) {
      throw new IllegalStateException("Thư chết đã được xử lý trước đó");
    }
  }

  private Letter open(TenantId tenant, UUID id) {
    Letter letter =
        TenantScope.call(
            tenant,
            () ->
                tx.execute(
                    s ->
                        letters
                            .find(tenant, id)
                            .orElseThrow(
                                () -> new NoSuchElementException("Không có thư chết " + id))));
    if (letter.resolved()) {
      throw new IllegalStateException("Thư chết đã được xử lý trước đó");
    }
    return letter;
  }
}
