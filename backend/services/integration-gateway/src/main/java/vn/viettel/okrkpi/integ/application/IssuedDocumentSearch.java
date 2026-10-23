package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.Result;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Use case tra cứu văn bản đã ban hành của đơn vị cho MOD-03 (QT-07, BR-07-13, API công khai {@code
 * searchEvidenceExternalDocs} do MOD-03 phục vụ): đổi đơn vị nội bộ sang mã đơn vị phía ngoài qua
 * {@code mapping_xref} rồi hỏi hệ văn bản; đơn vị chưa ánh xạ thì trả rỗng chứ không hỏi đối tác.
 */
public class IssuedDocumentSearch {

  public static final int MAX_LIMIT = 200;

  private final Connectors connectors;
  private final MappingXrefService mappings;
  private final Map<String, IssuedDocumentSource> sources = new HashMap<>();
  private final TransactionOperations tx;
  private final Clock clock;

  public IssuedDocumentSearch(
      Connectors connectors,
      MappingXrefService mappings,
      Collection<IssuedDocumentSource> sources,
      TransactionOperations tx,
      Clock clock) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.mappings = Objects.requireNonNull(mappings, "mappings");
    for (IssuedDocumentSource s : sources) {
      if (this.sources.putIfAbsent(s.connectorCode(), s) != null) {
        throw new IllegalStateException("Trùng nguồn tra cứu cho kết nối " + s.connectorCode());
      }
    }
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private record Target(Connector connector, String externalUnit) {}

  public Result search(
      TenantId tenant, String connectorCode, UUID orgUnitId, String q, int limit, String cursor) {
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit phải từ 1 đến 200");
    }
    if (q != null && q.length() > 200) {
      throw new IllegalArgumentException("Từ khoá tối đa 200 ký tự");
    }
    IssuedDocumentSource source = sources.get(connectorCode);
    if (source == null) {
      throw new NoSuchElementException("Chưa có nguồn tra cứu cho kết nối " + connectorCode);
    }
    LocalDate today = LocalDate.now(clock);
    Target target =
        TenantScope.call(
            tenant,
            () ->
                tx.execute(
                    s -> {
                      Connector c =
                          connectors
                              .findByCode(tenant, connectorCode)
                              .filter(Connector::enabled)
                              .orElseThrow(
                                  () ->
                                      new NoSuchElementException(
                                          "Tenant chưa bật kết nối " + connectorCode));
                      return new Target(
                          c,
                          mappings
                              .externalOf(tenant, c.id(), EntityType.DON_VI, orgUnitId, today)
                              .orElse(null));
                    }));
    if (target == null || target.externalUnit() == null) {
      return new Result(java.util.List.of(), null);
    }
    try {
      return source.search(target.connector(), target.externalUnit(), q, limit, cursor);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
