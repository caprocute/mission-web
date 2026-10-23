package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Số liệu nguồn của KR lấy từ chỉ tiêu KT-XH đã lưu (DV-13-06): KR gắn mã chỉ tiêu LGSP qua {@code
 * mapping_xref} loại {@code CHI_SO} (TL-03, {@code KeyResult} "Mã chỉ tiêu KT-XH của LGSP"); chỉ
 * giá trị đã công bố mới được trả (BR-16-05), chưa công bố thì coi như nguồn chưa có số liệu.
 */
public class KtxhIndicatorSource implements IndicatorSource {

  private final String code;
  private final KtxhValues values;
  private final TransactionOperations tx;

  public KtxhIndicatorSource(String code, KtxhValues values, TransactionOperations tx) {
    this.code = Objects.requireNonNull(code, "code");
    this.values = Objects.requireNonNull(values, "values");
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  @Override
  public String connectorCode() {
    return code;
  }

  @Override
  public Reading read(Connector connector, String externalIndicatorId) throws IOException {
    TenantId tenant = TenantId.of(connector.tenantId());
    KtxhValues.Value v =
        TenantScope.call(
            tenant,
            () ->
                tx.execute(
                    s ->
                        values
                            .latestPublished(tenant, connector.id(), externalIndicatorId)
                            .orElse(null)));
    if (v == null) {
      throw new IOException("Chỉ tiêu " + externalIndicatorId + " chưa có số liệu đã công bố");
    }
    return new Reading(v.value(), v.publishedAt());
  }
}
