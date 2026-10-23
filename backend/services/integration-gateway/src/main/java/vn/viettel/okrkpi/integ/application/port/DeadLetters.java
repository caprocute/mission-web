package vn.viettel.okrkpi.integ.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thư chết riêng mỗi kết nối ({@code integ.dead_letter}). */
public interface DeadLetters {

  record Letter(
      UUID id,
      UUID connectorId,
      String direction,
      UUID refMessageId,
      String errorCode,
      String errorDetail,
      Instant createdAt,
      UUID resolvedBy,
      Instant resolvedAt) {

    public boolean resolved() {
      return resolvedAt != null;
    }
  }

  void insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      String direction,
      UUID refMessageId,
      String errorCode,
      String errorDetail);

  Optional<Letter> find(TenantId tenant, UUID id);

  /**
   * Trang theo {@code (created_at, id)} tăng dần sau con trỏ; {@code connectorId} rỗng là mọi kết
   * nối.
   */
  List<Letter> page(
      TenantId tenant,
      UUID connectorId,
      boolean openOnly,
      Instant afterCreatedAt,
      UUID afterId,
      int limit);

  /** {@code false} khi thư đã được xử lý trước đó. */
  boolean resolve(TenantId tenant, UUID id, UUID actor, Instant at);
}
