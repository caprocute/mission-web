package vn.viettel.okrkpi.integ.application.rating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsBinding.Converted;
import vn.viettel.okrkpi.integ.application.outbound.ExternalRefs;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException.Reason;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.rating.SubjectChains.Subject;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Đổi EVT-12 sang dữ liệu chia sẻ lên CSDL quốc gia CBCCVC theo ví dụ TL-01 "Phong bì sự kiện"
 * (DV-13-09): người được đánh giá và người quyết định dùng mã ngoài {@code scheme=cbccvc} của kết
 * nối (ánh xạ do đồng bộ CSDL CBCCVC tạo, DV-13-06), chữ ký là tham chiếu {@code esign:<mã>}.
 *
 * <p>Quyết định thay sau hậu kiểm là một EVT-12 mới của cùng hồ sơ với {@code aggregateVersion} lớn
 * hơn: dữ liệu mang {@code supersedes} là {@code id} CloudEvents của bản đã gửi trước. Sự kiện đến
 * sau phiên bản mới hơn bị từ chối ({@link Reason#OUT_OF_ORDER}); giao lại cùng sự kiện cho đúng dữ
 * liệu cũ, nên pipeline chỉ ghi một thông điệp ra.
 */
public class RatingShareConverter {

  public static final String SCHEME = "cbccvc";

  private final String connectorCode;
  private final Connectors connectors;
  private final ExternalRefs refs;
  private final MappingXrefService xrefs;
  private final SubjectChains chains;
  private final ObjectMapper json;
  private final TransactionOperations tx;
  private final Clock clock;

  public RatingShareConverter(
      String connectorCode,
      Connectors connectors,
      ExternalRefs refs,
      MappingXrefService xrefs,
      SubjectChains chains,
      ObjectMapper json,
      TransactionOperations tx,
      Clock clock) {
    this.connectorCode = Objects.requireNonNull(connectorCode, "connectorCode");
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.refs = Objects.requireNonNull(refs, "refs");
    this.xrefs = Objects.requireNonNull(xrefs, "xrefs");
    this.chains = Objects.requireNonNull(chains, "chains");
    this.json = Objects.requireNonNull(json, "json");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public String connectorCode() {
    return connectorCode;
  }

  /** Khoá thực thể của hồ sơ đánh giá, cũng là {@code subject} của CloudEvents. */
  public static String subjectOf(RatingDecided r) {
    return "appraisal-profile/" + r.profileId();
  }

  public Converted convert(TenantId tenant, UUID eventId, RatingDecided r) {
    Objects.requireNonNull(eventId, "eventId");
    Object[] out = new Object[1];
    TenantContext.run(tenant, () -> out[0] = tx.execute(s -> convertInTx(tenant, eventId, r)));
    return (Converted) out[0];
  }

  private Converted convertInTx(TenantId tenant, UUID eventId, RatingDecided r) {
    Connector c =
        connectors
            .findByCode(tenant, connectorCode)
            .orElseThrow(
                () -> new NoSuchElementException("Tenant chưa cấu hình kết nối " + connectorCode));
    LocalDate today = LocalDate.now(clock);
    ObjectNode data = json.createObjectNode();
    data.set(
        "subjectRef",
        refs.require(tenant, c.id(), EntityType.NHAN_SU, r.personId(), SCHEME, today));
    data.put("flow", r.flow());
    data.put("rating", r.ratingCode());
    if (r.deciderId() != null) {
      Optional<String> decider =
          xrefs.externalOf(tenant, c.id(), EntityType.NHAN_SU, r.deciderId(), today);
      decider.ifPresent(
          id -> {
            ObjectNode ref = data.putObject("decidedBy");
            ref.put("scheme", SCHEME);
            ref.put("id", id);
          });
    }
    if (r.signRequestId() != null) {
      data.put("signatureRef", "esign:" + r.signRequestId());
    }
    UUID previous = chain(tenant, c.id(), subjectOf(r), eventId, r.aggregateVersion());
    if (previous != null) {
      data.put("supersedes", previous.toString());
    }
    return new Converted(subjectOf(r), data, Classification.C1);
  }

  /** Bản đã gửi trước mà sự kiện này thay; {@code null} với bản đầu tiên. */
  private UUID chain(TenantId tenant, UUID connectorId, String key, UUID eventId, int version) {
    Optional<Subject> current = chains.lock(tenant, connectorId, key);
    if (current.isEmpty()) {
      chains.insert(tenant, Ids.newUuidV7(), connectorId, key, eventId, version);
      return null;
    }
    Subject s = current.get();
    if (s.currentEventId().equals(eventId)) {
      return s.previousEventId();
    }
    if (version <= s.aggregateVersion()) {
      throw new OutboundRejectedException(
          Reason.OUT_OF_ORDER, "Đã gửi phiên bản mới hơn của hồ sơ đánh giá");
    }
    chains.advance(tenant, s.id(), eventId, s.currentEventId(), version);
    return s.currentEventId();
  }
}
