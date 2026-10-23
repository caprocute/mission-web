package vn.viettel.okrkpi.integ.application.anchor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.OutboundPipeline.Enqueued;
import vn.viettel.okrkpi.integ.application.alert.AdminAlerts;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.PendingReceipt;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.Unchecked;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.Window;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.OutboundEvent;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.ErrorText;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gửi bản neo gốc cell hằng ngày tới các bên độc lập và trả biên nhận cho ledger-service (DV-13-09,
 * TL-01 "Niêm phong Merkle, dấu thời gian và bản neo", TL-03 "neo hằng ngày tới ít nhất 2 bên").
 *
 * <ol>
 *   <li>{@link #onSeal}: EVT-19 cuối ngày (loại trong {@link AnchorSettings#sealKinds()}, cuối cửa
 *       sổ đúng nửa đêm theo {@link AnchorSettings#zone()}) ghi một dòng {@code anchor_window} cho
 *       (cell, ngày); EVT-19 của các tenant khác cùng ngày và sự kiện giao lại không ghi thêm.
 *   <li>{@link #fanOutDue}: hỏi ledger-service gói neo cấp cell; có thì ghi một {@code
 *       outbound_message} và một {@code anchor_dispatch} cho mỗi đích trong cùng giao dịch, chưa có
 *       thì hỏi lại với khoảng chờ tăng dần.
 *   <li>Adapter của đích gọi {@link #receipt} khi đích trả biên nhận; thông điệp chỉ xong khi có
 *       biên nhận, nên đích lỗi được pipeline gửi lại theo lịch thử lại.
 *   <li>{@link #relayReceipts}: chuyển từng biên nhận cho ledger-service đúng một lần mỗi đích.
 *   <li>{@link #checkShortfalls}: hết thời gian chờ mà ngày chưa đủ {@link
 *       AnchorSettings#minReceipts()} biên nhận thì cảnh báo quản trị một lần.
 * </ol>
 */
public class AnchorService {

  /** Loại thông điệp phía hệ ngoài. */
  public static final String MESSAGE_TYPE = "BAN_NEO_GOC_CELL";

  static final Duration MAX_FETCH_BACKOFF = Duration.ofHours(1);

  private static final Logger LOG = LoggerFactory.getLogger(AnchorService.class);

  private final AnchorSettings settings;
  private final AnchorRecords records;
  private final AnchorLedger ledger;
  private final OutboundPipeline pipeline;
  private final Connectors connectors;
  private final AdminAlerts alerts;
  private final CloudEventsMapper mapper;
  private final Function<String, OutboundPolicy> policies;
  private final ObjectMapper json;
  private final TransactionOperations tx;
  private final Clock clock;

  public AnchorService(
      AnchorSettings settings,
      AnchorRecords records,
      AnchorLedger ledger,
      OutboundPipeline pipeline,
      Connectors connectors,
      AdminAlerts alerts,
      CloudEventsMapper mapper,
      Function<String, OutboundPolicy> policies,
      ObjectMapper json,
      TransactionOperations tx,
      Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.records = Objects.requireNonNull(records, "records");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.json = Objects.requireNonNull(json, "json");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public AnchorSettings settings() {
    return settings;
  }

  /** Mã kết nối của các đích neo, để lắp adapter chiều ra. */
  public Map<AnchorTarget, String> targets() {
    return settings.targets();
  }

  /** Niêm phong có phải bản neo trong ngày không. */
  public boolean isDailyAnchor(SealNotice seal) {
    return settings.sealKinds().contains(seal.sealKind())
        && seal.windowEnd().atZone(settings.zone()).toLocalTime().equals(LocalTime.MIDNIGHT);
  }

  /** Ghi ngày cần neo từ EVT-19; {@code true} khi đây là sự kiện đầu tiên của ngày. */
  public boolean onSeal(SealNotice seal) {
    if (!isDailyAnchor(seal)) {
      return false;
    }
    TenantId operator = settings.operatorTenant();
    return Scoped.tx(
        operator,
        tx,
        () ->
            records.registerWindow(
                operator, Ids.newUuidV7(), settings.cellCode(), seal.windowEnd(), seal.eventId()));
  }

  /** Tạo bản neo cho các ngày đến hạn đã có gói neo; trả số ngày đã gửi đi. */
  public int fanOutDue(int limit) {
    TenantId operator = settings.operatorTenant();
    Integer done =
        Scoped.tx(
            operator,
            tx,
            () -> {
              int n = 0;
              for (Window w : records.lockDueWindows(operator, clock.instant(), limit)) {
                if (fanOut(operator, w)) {
                  n++;
                }
              }
              return n;
            });
    return done == null ? 0 : done;
  }

  private boolean fanOut(TenantId operator, Window w) {
    Instant now = clock.instant();
    Optional<CellAnchor> anchor;
    try {
      anchor = ledger.cellAnchor(w.cellCode(), w.windowEnd());
    } catch (IOException | RuntimeException e) {
      LOG.warn("Chưa lấy được gói neo cell {}: {}", w.cellCode(), ErrorText.code(e));
      anchor = Optional.empty();
    }
    if (anchor.isEmpty()) {
      int attempts = w.attempts() + 1;
      records.postponeWindow(operator, w.id(), attempts, now.plus(backoff(attempts)));
      return false;
    }
    CellAnchor a = anchor.get();
    if (!a.cellCode().equals(w.cellCode()) || !a.windowEnd().equals(w.windowEnd())) {
      throw new IllegalStateException("Gói neo không khớp ngày cần neo");
    }
    UUID eventId = eventIdOf(a);
    ObjectNode data = data(a);
    for (Map.Entry<AnchorTarget, String> t : settings.targets().entrySet()) {
      String code = t.getValue();
      Connector c =
          connectors
              .findByCode(operator, code)
              .orElseThrow(
                  () ->
                      new NoSuchElementException("Tenant vận hành chưa cấu hình kết nối " + code));
      byte[] payload = mapper.toJson(event(operator, eventId, a, data), policies.apply(code));
      Enqueued q = pipeline.enqueue(operator, code, eventId, MESSAGE_TYPE, payload);
      if (q.id() != null) {
        records.insertDispatch(
            operator, Ids.newUuidV7(), w.id(), a.cellSealId(), t.getKey(), c.id(), q.id());
      }
    }
    records.windowFannedOut(operator, w.id(), a.cellSealId(), now);
    return true;
  }

  Duration backoff(int attempts) {
    Duration d = settings.fetchRetry();
    for (int i = 1; i < attempts && d.compareTo(MAX_FETCH_BACKOFF) < 0; i++) {
      d = d.multipliedBy(2);
    }
    return d.compareTo(MAX_FETCH_BACKOFF) > 0 ? MAX_FETCH_BACKOFF : d;
  }

  /** Mã CloudEvents của bản neo: như nhau ở mọi đích để bên nhận và kiểm toán đối chiếu. */
  static UUID eventIdOf(CellAnchor a) {
    return UUID.nameUUIDFromBytes(("anchor:" + a.cellSealId()).getBytes(StandardCharsets.UTF_8));
  }

  private OutboundEvent event(TenantId operator, UUID eventId, CellAnchor a, ObjectNode data) {
    return new OutboundEvent(
        eventId,
        EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG,
        "cell-seal/" + a.cellSealId(),
        a.tsaGenTime() == null ? clock.instant() : a.tsaGenTime(),
        null,
        operator.value(),
        Classification.C1,
        null,
        data,
        Classification.C1);
  }

  ObjectNode data(CellAnchor a) {
    HexFormat hex = HexFormat.of();
    Base64.Encoder b64 = Base64.getEncoder();
    ObjectNode d = json.createObjectNode();
    d.put("cell", a.cellCode());
    d.put("cellSealId", a.cellSealId().toString());
    if (a.windowStart() != null) {
      d.put("windowStart", a.windowStart().toString());
    }
    d.put("windowEnd", a.windowEnd().toString());
    d.put("cellRoot", hex.formatHex(a.cellRoot()));
    if (a.prevCellRoot() != null) {
      d.put("prevCellRoot", hex.formatHex(a.prevCellRoot()));
    }
    d.put("signature", b64.encodeToString(a.signature()));
    if (a.signerCertSha256() != null) {
      d.put("signerCertSha256", a.signerCertSha256());
    }
    d.put("tsaToken", b64.encodeToString(a.tsaToken()));
    if (a.tsaGenTime() != null) {
      d.put("tsaGenTime", a.tsaGenTime().toString());
    }
    return d;
  }

  /**
   * Ghi biên nhận của đích cho thông điệp {@code messageId}; gọi trong giao dịch gửi của pipeline.
   *
   * @throws IllegalArgumentException khi biên nhận rỗng (đích chưa xác nhận, pipeline gửi lại)
   * @throws NoSuchElementException khi thông điệp không phải bản neo
   */
  public void receipt(TenantId tenant, UUID messageId, byte[] receipt) {
    if (receipt == null || receipt.length == 0) {
      throw new IllegalArgumentException("Đích neo không trả biên nhận");
    }
    UUID dispatch =
        records
            .dispatchOf(tenant, messageId)
            .orElseThrow(() -> new NoSuchElementException("Thông điệp không phải bản neo"));
    records.recordReceipt(tenant, dispatch, receipt, clock.instant());
  }

  /** Chuyển biên nhận chưa chuyển cho ledger-service; trả số biên nhận đã chuyển. */
  public int relayReceipts(int limit) {
    TenantId operator = settings.operatorTenant();
    Integer done =
        Scoped.tx(
            operator,
            tx,
            () -> {
              int n = 0;
              for (PendingReceipt p : records.lockPendingReceipts(operator, limit)) {
                try {
                  ledger.submit(
                      new AnchorLedger.Receipt(
                          p.id(),
                          p.cellSealId(),
                          p.target(),
                          p.connectorCode(),
                          p.receipt(),
                          p.receivedAt()));
                  records.receiptRelayed(operator, p.id(), clock.instant());
                  n++;
                } catch (IOException | RuntimeException e) {
                  LOG.warn("Chưa chuyển được biên nhận neo {}: {}", p.id(), ErrorText.code(e));
                  records.relayFailed(operator, p.id(), p.relayAttempts() + 1);
                }
              }
              return n;
            });
    return done == null ? 0 : done;
  }

  /** Kiểm số biên nhận của các ngày đã quá thời gian chờ; trả số ngày bị cảnh báo thiếu. */
  public int checkShortfalls(int limit) {
    TenantId operator = settings.operatorTenant();
    Instant now = clock.instant();
    Integer alerted =
        Scoped.tx(
            operator,
            tx,
            () -> {
              int n = 0;
              List<Unchecked> due =
                  records.lockUnchecked(operator, now.minus(settings.grace()), limit);
              for (Unchecked u : due) {
                if (u.receipts() < settings.minReceipts()) {
                  alerts.anchorShortfall(
                      operator,
                      u.id(),
                      u.cellCode(),
                      u.windowEnd(),
                      u.receipts(),
                      settings.minReceipts());
                  n++;
                }
                records.windowChecked(operator, u.id(), now);
              }
              return n;
            });
    return alerted == null ? 0 : alerted;
  }
}
