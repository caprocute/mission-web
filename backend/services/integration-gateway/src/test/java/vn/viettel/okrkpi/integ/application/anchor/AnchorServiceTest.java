package vn.viettel.okrkpi.integ.application.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.OutboundPipeline.Enqueued;
import vn.viettel.okrkpi.integ.application.OutboundPipeline.Result;
import vn.viettel.okrkpi.integ.application.alert.AdminAlerts;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.PendingReceipt;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.Unchecked;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords.Window;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class AnchorServiceTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static final TenantId OPERATOR =
      TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-0000001309a0"));
  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

  /** Nửa đêm 23/10/2026 giờ Việt Nam. */
  static final Instant MIDNIGHT = Instant.parse("2026-10-22T17:00:00Z");

  final AnchorRecords records = mock(AnchorRecords.class);
  final AnchorLedger ledger = mock(AnchorLedger.class);
  final OutboundPipeline pipeline = mock(OutboundPipeline.class);
  final Connectors connectors = mock(Connectors.class);
  final AdminAlerts alerts = mock(AdminAlerts.class);
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Map<AnchorTarget, String> targets =
      new EnumMap<>(Map.of(AnchorTarget.KHO_VPTW, "NEO_KHO", AnchorTarget.TSA_DOC_LAP, "NEO_TSA"));
  final AnchorSettings settings =
      new AnchorSettings(
          OPERATOR, "cell-hcm", null, null, targets, 2, Duration.ofHours(6), Duration.ofMinutes(1));
  final AnchorService service =
      new AnchorService(
          settings,
          records,
          ledger,
          pipeline,
          connectors,
          alerts,
          new CloudEventsMapper("cell-hcm", "https://api.okr.viettel.vn/schemas", JSON, clock),
          OutboundPolicy::defaults,
          JSON,
          TransactionOperations.withoutTransaction(),
          clock);

  static Connector connector(String code) {
    return new Connector(
        UUID.nameUUIDFromBytes(code.getBytes()),
        OPERATOR.value(),
        code,
        Protocol.REST,
        "https://neo.invalid",
        "kv/integ/neo",
        Direction.RA,
        null,
        true);
  }

  static CellAnchor anchor() {
    byte[] root = new byte[32];
    root[0] = 1;
    return new CellAnchor(
        UUID.fromString("0192f1a2-0000-7000-8000-0000001309e1"),
        "cell-hcm",
        MIDNIGHT.minus(Duration.ofDays(1)),
        MIDNIGHT,
        root,
        new byte[32],
        new byte[] {9, 9},
        "ab12",
        new byte[] {7},
        MIDNIGHT.plusSeconds(3));
  }

  @Test
  void settingsDefaultsAndValidation() {
    assertThat(settings.zone().getId()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(settings.sealKinds()).containsExactly("HOURLY");
    assertThat(service.settings()).isSameAs(settings);
    assertThat(service.targets()).containsKeys(AnchorTarget.KHO_VPTW, AnchorTarget.TSA_DOC_LAP);
    assertThatThrownBy(
            () -> new AnchorSettings(OPERATOR, "c", null, Set.of("X"), Map.of(), 2, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnchorSettings(OPERATOR, "c", null, null, null, 2, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnchorSettings(OPERATOR, "c", null, null, targets, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    AnchorSettings d = new AnchorSettings(OPERATOR, "c", null, null, targets, 1, null, null);
    assertThat(d.grace()).isEqualTo(Duration.ofHours(6));
    assertThat(d.fetchRetry()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void cellAnchorRequiresThirtyTwoByteRoots() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new CellAnchor(
                    id,
                    "c",
                    null,
                    MIDNIGHT,
                    new byte[31],
                    null,
                    new byte[1],
                    null,
                    new byte[1],
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CellAnchor(
                    id,
                    "c",
                    null,
                    MIDNIGHT,
                    new byte[32],
                    new byte[3],
                    new byte[1],
                    null,
                    new byte[1],
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyEndOfDaySealsOfConfiguredKindAreAnchors() {
    SealNotice daily =
        new SealNotice(UUID.randomUUID(), UUID.randomUUID(), "HOURLY", null, MIDNIGHT);
    SealNotice midday =
        new SealNotice(
            UUID.randomUUID(), UUID.randomUUID(), "HOURLY", null, MIDNIGHT.plusSeconds(3600));
    SealNotice other =
        new SealNotice(UUID.randomUUID(), UUID.randomUUID(), "PERIOD_CLOSE", null, MIDNIGHT);
    when(records.registerWindow(eq(OPERATOR), any(), eq("cell-hcm"), eq(MIDNIGHT), any()))
        .thenReturn(true);

    assertThat(service.onSeal(daily)).isTrue();
    assertThat(service.onSeal(midday)).isFalse();
    assertThat(service.onSeal(other)).isFalse();
    verify(records)
        .registerWindow(eq(OPERATOR), any(), eq("cell-hcm"), eq(MIDNIGHT), eq(daily.eventId()));
  }

  @Test
  void windowWithoutPackageIsPostponedWithGrowingBackoff() throws IOException {
    UUID w1 = UUID.randomUUID();
    UUID w2 = UUID.randomUUID();
    when(records.lockDueWindows(OPERATOR, NOW, 10))
        .thenReturn(
            List.of(
                new Window(w1, "cell-hcm", MIDNIGHT, 0), new Window(w2, "cell-hcm", MIDNIGHT, 3)));
    when(ledger.cellAnchor("cell-hcm", MIDNIGHT))
        .thenReturn(Optional.empty())
        .thenThrow(new IOException("ledger-service chưa sẵn sàng"));

    assertThat(service.fanOutDue(10)).isZero();

    verify(records).postponeWindow(OPERATOR, w1, 1, NOW.plus(Duration.ofMinutes(1)));
    verify(records).postponeWindow(OPERATOR, w2, 4, NOW.plus(Duration.ofMinutes(8)));
    assertThat(service.backoff(30)).isEqualTo(AnchorService.MAX_FETCH_BACKOFF);
  }

  @Test
  void packageFansOutOneMessagePerTargetWithTextOnlyData() throws Exception {
    UUID w = UUID.randomUUID();
    UUID m1 = UUID.randomUUID();
    CellAnchor a = anchor();
    when(records.lockDueWindows(OPERATOR, NOW, 10))
        .thenReturn(List.of(new Window(w, "cell-hcm", MIDNIGHT, 2)));
    when(ledger.cellAnchor("cell-hcm", MIDNIGHT)).thenReturn(Optional.of(a));
    when(connectors.findByCode(OPERATOR, "NEO_KHO")).thenReturn(Optional.of(connector("NEO_KHO")));
    when(connectors.findByCode(OPERATOR, "NEO_TSA")).thenReturn(Optional.of(connector("NEO_TSA")));
    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    when(pipeline.enqueue(
            eq(OPERATOR), eq("NEO_KHO"), any(), eq(AnchorService.MESSAGE_TYPE), payload.capture()))
        .thenReturn(new Enqueued(Result.QUEUED, m1));
    when(pipeline.enqueue(eq(OPERATOR), eq("NEO_TSA"), any(), any(), any()))
        .thenReturn(new Enqueued(Result.DUPLICATE, null));

    assertThat(service.fanOutDue(10)).isEqualTo(1);

    verify(records)
        .insertDispatch(
            eq(OPERATOR),
            any(),
            eq(w),
            eq(a.cellSealId()),
            eq(AnchorTarget.KHO_VPTW),
            eq(connector("NEO_KHO").id()),
            eq(m1));
    verify(records, never())
        .insertDispatch(any(), any(), any(), any(), eq(AnchorTarget.TSA_DOC_LAP), any(), any());
    verify(records).windowFannedOut(OPERATOR, w, a.cellSealId(), NOW);
    JsonNode ce = JSON.readTree(payload.getValue());
    assertThat(ce.path("id").asText()).isEqualTo(AnchorService.eventIdOf(a).toString());
    assertThat(ce.path("type").asText()).isEqualTo("vn.viettel.okrkpi.ledger.seal.created.v1");
    assertThat(ce.path("subject").asText()).isEqualTo("cell-seal/" + a.cellSealId());
    assertThat(ce.path("source").asText()).isEqualTo("urn:vt-okrkpi:cell-hcm:ledger");
    assertThat(ce.path("classification").asText()).isEqualTo("C1");
    assertThat(ce.at("/data/cellRoot").asText()).isEqualTo("01" + "00".repeat(31));
    assertThat(ce.at("/data/prevCellRoot").asText()).isEqualTo("00".repeat(32));
    assertThat(ce.at("/data/signature").asText()).isEqualTo("CQk=");
    assertThat(ce.at("/data/tsaToken").asText()).isEqualTo("Bw==");
    assertThat(ce.at("/data/windowEnd").asText()).isEqualTo(MIDNIGHT.toString());
    assertThat(ce.at("/data/signerCertSha256").asText()).isEqualTo("ab12");
  }

  @Test
  void packageEndingBeforeMidnightIsAccepted() throws IOException {
    UUID w = UUID.randomUUID();
    CellAnchor a = anchor();
    when(records.lockDueWindows(OPERATOR, NOW, 10))
        .thenReturn(List.of(new Window(w, "cell-hcm", a.windowEnd().plusSeconds(3600), 0)));
    when(ledger.cellAnchor(any(), any())).thenReturn(Optional.of(a));
    when(connectors.findByCode(eq(OPERATOR), any())).thenReturn(Optional.of(connector("NEO_KHO")));
    when(pipeline.enqueue(any(), any(), any(), any(), any()))
        .thenReturn(new Enqueued(Result.QUEUED, UUID.randomUUID()));

    assertThat(service.fanOutDue(10)).isEqualTo(1);
  }

  @Test
  void minimalPackageOmitsOptionalFields() {
    CellAnchor a =
        new CellAnchor(
            UUID.randomUUID(),
            "cell-hcm",
            null,
            MIDNIGHT,
            new byte[32],
            null,
            new byte[] {1},
            null,
            new byte[] {2},
            null);
    JsonNode d = service.data(a);
    assertThat(d.has("windowStart")).isFalse();
    assertThat(d.has("prevCellRoot")).isFalse();
    assertThat(d.has("signerCertSha256")).isFalse();
    assertThat(d.has("tsaGenTime")).isFalse();
  }

  @Test
  void mismatchedPackageOrMissingConnectorFails() throws IOException {
    UUID w = UUID.randomUUID();
    when(records.lockDueWindows(OPERATOR, NOW, 10))
        .thenReturn(List.of(new Window(w, "cell-hcm", MIDNIGHT.plusSeconds(86400), 0)));
    when(ledger.cellAnchor(any(), any())).thenReturn(Optional.of(anchor()));
    assertThatThrownBy(() -> service.fanOutDue(10)).isInstanceOf(IllegalStateException.class);

    when(records.lockDueWindows(OPERATOR, NOW, 10))
        .thenReturn(List.of(new Window(w, "cell-hcm", MIDNIGHT, 0)));
    when(connectors.findByCode(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.fanOutDue(10)).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void receiptIsRecordedOnlyForAnchorMessages() {
    UUID message = UUID.randomUUID();
    UUID dispatch = UUID.randomUUID();
    when(records.dispatchOf(OPERATOR, message)).thenReturn(Optional.of(dispatch));

    service.receipt(OPERATOR, message, new byte[] {1});
    verify(records).recordReceipt(OPERATOR, dispatch, new byte[] {1}, NOW);

    assertThatThrownBy(() -> service.receipt(OPERATOR, message, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.receipt(OPERATOR, message, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.receipt(OPERATOR, UUID.randomUUID(), new byte[] {1}))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void relayMarksSuccessAndCountsFailure() throws IOException {
    PendingReceipt ok =
        new PendingReceipt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            AnchorTarget.KHO_VPTW,
            "NEO_KHO",
            new byte[] {1},
            NOW,
            0);
    PendingReceipt bad =
        new PendingReceipt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            AnchorTarget.TSA_DOC_LAP,
            "NEO_TSA",
            new byte[] {2},
            NOW,
            4);
    when(records.lockPendingReceipts(OPERATOR, 10)).thenReturn(List.of(ok, bad));
    org.mockito.Mockito.doAnswer(
            inv -> {
              AnchorLedger.Receipt r = inv.getArgument(0);
              if (r.target() == AnchorTarget.TSA_DOC_LAP) {
                throw new IOException("503");
              }
              return null;
            })
        .when(ledger)
        .submit(any());

    assertThat(service.relayReceipts(10)).isEqualTo(1);

    verify(records).receiptRelayed(OPERATOR, ok.id(), NOW);
    verify(records).relayFailed(OPERATOR, bad.id(), 5);
    verify(records, never()).receiptRelayed(eq(OPERATOR), eq(bad.id()), any());
  }

  @Test
  void shortfallAlertsOnceAndMarksEveryDayChecked() {
    Unchecked shortDay = new Unchecked(UUID.randomUUID(), "cell-hcm", MIDNIGHT, true, 1);
    Unchecked fullDay = new Unchecked(UUID.randomUUID(), "cell-hcm", MIDNIGHT, true, 2);
    when(records.lockUnchecked(OPERATOR, NOW.minus(Duration.ofHours(6)), 10))
        .thenReturn(List.of(shortDay, fullDay));

    assertThat(service.checkShortfalls(10)).isEqualTo(1);

    verify(alerts).anchorShortfall(OPERATOR, shortDay.id(), "cell-hcm", MIDNIGHT, 1, 2);
    verify(alerts, never())
        .anchorShortfall(any(), eq(fullDay.id()), any(), any(), anyInt(), anyInt());
    verify(records).windowChecked(OPERATOR, shortDay.id(), NOW);
    verify(records).windowChecked(OPERATOR, fullDay.id(), NOW);
  }

  @Test
  void sealNoticeRequiresIdentifiers() {
    assertThatThrownBy(() -> new SealNotice(null, UUID.randomUUID(), "HOURLY", null, MIDNIGHT))
        .isInstanceOf(NullPointerException.class);
  }
}
