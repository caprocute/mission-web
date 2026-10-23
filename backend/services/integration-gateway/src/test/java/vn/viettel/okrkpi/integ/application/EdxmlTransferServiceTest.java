package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService.Attachment;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService.Request;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Due;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Tracked;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Gửi văn bản EdXML ra Trục cho MOD-06 (DV-13-05): kiểm tra đầu vào, chống trùng, trạng thái. */
class EdxmlTransferServiceTest {

  static final TenantId T = TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-00000013e001"));
  static final UUID TRANSFER = UUID.fromString("0192f1a2-0000-7000-8000-00000013e002");
  static final Instant AT = Instant.parse("2026-10-23T03:00:00Z");
  static final Connector BTC =
      new Connector(
          UUID.randomUUID(),
          T.value(),
          "BTC_TW",
          Protocol.XROAD,
          "https://ss.invalid/r1",
          null,
          Direction.RA,
          null,
          true);

  final Connectors connectors = mock(Connectors.class);
  final OutboundPipeline pipeline = mock(OutboundPipeline.class);
  final OutboundMessages messages = mock(OutboundMessages.class);
  final DeadLetters letters = mock(DeadLetters.class);
  final EdxmlTransferService service =
      new EdxmlTransferService(
          connectors,
          pipeline,
          messages,
          letters,
          (c, r) -> ("<edXML " + c.code() + ">").getBytes(),
          Set.of("BTC_TW", "VPTW"),
          10,
          TransactionOperations.withoutTransaction());

  {
    when(connectors.findByCode(T, "BTC_TW")).thenReturn(Optional.of(BTC));
    when(connectors.findById(T, BTC.id())).thenReturn(Optional.of(BTC));
  }

  static Request request(String destination, byte[]... files) {
    return new Request(
        TRANSFER,
        UUID.randomUUID(),
        destination,
        "Hồ sơ cán bộ gửi Ban Tổ chức Trung ương",
        "12/HS-BTC",
        java.util.Arrays.stream(files).map(f -> new Attachment("ho-so.p7m", null, f)).toList());
  }

  static Tracked tracked(OutboundStatus s) {
    return new Tracked(UUID.randomUUID(), BTC.id(), s, 2, AT, "BN-1", AT);
  }

  @Test
  void xepHangMotLanVaGuiLaiCungMaTraTrangThaiCu() {
    when(messages.bySource(T, TRANSFER))
        .thenReturn(Optional.empty(), Optional.of(tracked(OutboundStatus.CHO)));
    var s = service.submit(T, request("BTC_TW", new byte[] {1, 2}));
    assertThat(s.status()).isEqualTo(OutboundStatus.CHO);
    assertThat(s.destination()).isEqualTo("BTC_TW");
    verify(pipeline).enqueue(eq(T), eq("BTC_TW"), eq(TRANSFER), eq("EDXML"), any());

    when(messages.bySource(T, TRANSFER))
        .thenReturn(Optional.of(tracked(OutboundStatus.DA_XAC_NHAN)));
    assertThat(service.submit(T, request("BTC_TW", new byte[] {1})).ackRef()).isEqualTo("BN-1");
    verify(pipeline).enqueue(any(), any(), any(), any(), any());
  }

  @Test
  void noiNhanChuaCauHinhHoacTatBiTuChoi() {
    when(messages.bySource(T, TRANSFER)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(T, request("VPTW", new byte[] {1})))
        .isInstanceOfSatisfying(
            TransferRejectedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(TransferRejectedException.Reason.DESTINATION_NOT_CONFIGURED));
    assertThatThrownBy(() -> service.submit(T, request("KHAC", new byte[] {1})))
        .isInstanceOf(TransferRejectedException.class);
    assertThatThrownBy(() -> service.submit(T, request(null, new byte[] {1})))
        .isInstanceOf(TransferRejectedException.class);
    when(connectors.findByCode(T, "BTC_TW"))
        .thenReturn(
            Optional.of(
                new Connector(
                    BTC.id(),
                    T.value(),
                    "BTC_TW",
                    Protocol.XROAD,
                    "https://x",
                    null,
                    Direction.VAO,
                    null,
                    true)));
    assertThatThrownBy(() -> service.submit(T, request("BTC_TW", new byte[] {1})))
        .isInstanceOf(TransferRejectedException.class);
    verify(pipeline, never()).enqueue(any(), any(), any(), any(), any());
  }

  @Test
  void dauVaoSaiHoacQuaTran() {
    assertThatThrownBy(() -> service.submit(T, request("BTC_TW", new byte[11])))
        .isInstanceOfSatisfying(
            TransferRejectedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(TransferRejectedException.Reason.PAYLOAD_TOO_LARGE));
    assertThatThrownBy(() -> service.submit(T, request("BTC_TW")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.submit(T, new Request(null, null, "BTC_TW", "x", null, List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.submit(
                    T,
                    new Request(
                        TRANSFER,
                        null,
                        "BTC_TW",
                        " ",
                        null,
                        List.of(new Attachment("a", null, new byte[] {1})))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.submit(
                    T,
                    new Request(
                        TRANSFER,
                        null,
                        "BTC_TW",
                        "x",
                        null,
                        List.of(new Attachment(" ", null, new byte[] {1})))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EdxmlTransferService(
                    connectors,
                    pipeline,
                    messages,
                    letters,
                    (c, r) -> new byte[0],
                    Set.of(),
                    0,
                    TransactionOperations.withoutTransaction()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Attachment("a", null, new byte[] {1, 2}).toString()).contains("2 byte");
  }

  @Test
  void trangThaiLoiKemMaThuChet() {
    Tracked failed = tracked(OutboundStatus.LOI);
    when(messages.bySource(T, TRANSFER)).thenReturn(Optional.of(failed));
    when(letters.openErrorCode(T, failed.id())).thenReturn(Optional.of("IOException"));
    var s = service.status(T, TRANSFER).orElseThrow();
    assertThat(s.lastError()).isEqualTo("IOException");
    assertThat(s.attempts()).isEqualTo(2);
    when(messages.bySource(T, TRANSFER)).thenReturn(Optional.empty());
    assertThat(service.status(T, TRANSFER)).isEmpty();
  }

  @Test
  void workerChiGuiCacMaDuocChon() {
    OutboundPipeline real =
        new OutboundPipeline(
            connectors,
            messages,
            mock(vn.viettel.okrkpi.integ.application.port.InboxMessages.class),
            letters,
            mock(vn.viettel.okrkpi.integ.application.port.PayloadCipher.class),
            List.of(),
            new vn.viettel.okrkpi.integ.domain.RetrySchedule(
                java.time.Duration.ofSeconds(1), 2, java.time.Duration.ofMinutes(1), 3),
            java.time.Duration.ofMinutes(1),
            TransactionOperations.withoutTransaction(),
            java.time.Clock.systemUTC());
    when(messages.due(anyInt(), eq(Set.of("BTC_TW")), eq(true))).thenReturn(List.<Due>of());
    assertThat(real.dispatchDue(10, Set.of("BTC_TW"), true)).isZero();
    verify(messages).due(10, Set.of("BTC_TW"), true);
  }
}
