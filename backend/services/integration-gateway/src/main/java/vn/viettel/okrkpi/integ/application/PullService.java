package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.Connectors.Active;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Các bước của route kéo theo lịch: chọn kết nối đang bật của mọi tenant, đọc cấu hình và con trỏ
 * trong ngữ cảnh tenant, gọi adapter ngoài giao dịch (trong circuit breaker của route), tiến con
 * trỏ sau khi xử lý cả trang.
 */
public class PullService {

  /** Một lượt kéo của một tenant: kết nối và con trỏ hiện tại. */
  public record Pull(TenantId tenant, Connector connector, String cursor) {}

  private final Connectors connectors;
  private final SyncCursorService cursors;
  private final TransactionOperations tx;

  public PullService(Connectors connectors, SyncCursorService cursors, TransactionOperations tx) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  /** Kết nối đang bật có mã {@code code} của mọi tenant. */
  public List<Active> targets(String code) {
    return connectors.enabled().stream().filter(a -> a.code().equals(code)).toList();
  }

  public Pull prepare(InboundConnector source, Active target) {
    TenantId tenant = TenantId.of(target.tenantId());
    Connector connector =
        TenantScope.call(
            tenant,
            () ->
                tx.execute(
                    s ->
                        connectors
                            .findById(tenant, target.id())
                            .orElseThrow(() -> new NoSuchElementException("Không có kết nối"))));
    String cursor = cursors.load(tenant, connector.id(), source.stream(), source.initialCursor());
    return new Pull(tenant, connector, cursor);
  }

  public Page fetch(InboundConnector source, Pull pull) throws IOException {
    Page page = source.fetch(pull.connector(), pull.cursor());
    return page == null ? new Page(List.of(), pull.cursor(), null) : page;
  }

  public void advance(InboundConnector source, Pull pull, Page page) {
    if (page.nextCursor() != null && !page.nextCursor().equals(pull.cursor())) {
      cursors.advance(
          pull.tenant(),
          pull.connector().id(),
          source.stream(),
          page.nextCursor(),
          page.sourceTime());
    }
  }
}
