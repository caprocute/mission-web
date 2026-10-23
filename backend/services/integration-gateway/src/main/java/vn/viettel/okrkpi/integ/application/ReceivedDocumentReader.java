package vn.viettel.okrkpi.integ.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Raw;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Located;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser.Parsed;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Toàn văn và đơn vị nhận của văn bản chỉ đạo đã phát EVT-24 (CORE-02-16), cho MOD-02 dựng màn hình
 * văn bản đến (SCR-0502). Mã văn bản nội bộ tra ngược qua {@code mapping_xref} {@code VAN_BAN}, nội
 * dung giải mã từ thông điệp vào đã phát mới nhất, đơn vị nhận ánh xạ qua {@code DON_VI}. Văn bản
 * không còn thông điệp (hết hạn lưu) trả rỗng.
 */
public class ReceivedDocumentReader {

  /** Kết quả cho mission-core; {@code recipientUnitId} rỗng khi đơn vị nhận đã bỏ ánh xạ. */
  public record ReceivedDocument(UUID recipientUnitId, Parsed content) {}

  private final MappingXrefs mappings;
  private final InboxMessages inbox;
  private final PayloadCipher cipher;
  private final ReceivedDocumentParser parser;
  private final TransactionOperations tx;
  private final Clock clock;

  public ReceivedDocumentReader(
      MappingXrefs mappings,
      InboxMessages inbox,
      PayloadCipher cipher,
      ReceivedDocumentParser parser,
      TransactionOperations tx,
      Clock clock) {
    this.mappings = Objects.requireNonNull(mappings, "mappings");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Optional<ReceivedDocument> read(TenantId tenant, UUID documentId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(documentId, "documentId");
    return TenantScope.call(tenant, () -> tx.execute(s -> load(tenant, documentId)));
  }

  private Optional<ReceivedDocument> load(TenantId tenant, UUID documentId) {
    LocalDate today = LocalDate.now(clock);
    List<Located> found =
        mappings.byInternalAnyConnector(tenant, EntityType.VAN_BAN, documentId, today);
    for (Located l : found) {
      Optional<Raw> raw = inbox.latestEmitted(tenant, l.connectorId(), l.mapping().externalId());
      if (raw.isEmpty()) {
        continue;
      }
      Raw r = raw.get();
      byte[] plain = cipher.open(tenant, InboundPipeline.RAW_COLUMN, r.id(), r.rawEnc());
      Parsed content = parser.parse(r.messageType(), plain);
      UUID unit =
          content.recipientExternalId() == null || content.recipientExternalId().isBlank()
              ? null
              : mappings
                  .byExternal(
                      tenant,
                      l.connectorId(),
                      EntityType.DON_VI,
                      content.recipientExternalId(),
                      today)
                  .map(MappingXrefs.Mapping::internalId)
                  .orElse(null);
      return Optional.of(new ReceivedDocument(unit, content));
    }
    return Optional.empty();
  }
}
