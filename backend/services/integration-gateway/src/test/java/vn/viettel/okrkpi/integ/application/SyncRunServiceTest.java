package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.InboundPipeline.Receipt;
import vn.viettel.okrkpi.integ.application.InboundPipeline.Result;
import vn.viettel.okrkpi.integ.application.PullService.Pull;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.Connectors.Active;
import vn.viettel.okrkpi.integ.application.port.DeadLetters.Letter;
import vn.viettel.okrkpi.integ.application.port.SyncRuns;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Due;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Kind;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Run;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Status;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lần đồng bộ kéo hết trang (DV-13-06) với cổng giả lập. */
class SyncRunServiceTest {

  static final TenantId T = TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-00000013c601"));
  static final Instant NOW = Instant.parse("2026-10-23T18:30:00Z");
  static final UUID RUN = UUID.fromString("0192f1a2-0000-7000-8000-00000013c610");
  static final Connector CBCCVC = connector("CBCCVC", Direction.VAO, "0 0 1 * * *");

  static Connector connector(String code, Direction direction, String cron) {
    return new Connector(
        UUID.nameUUIDFromBytes(code.getBytes()),
        T.value(),
        code,
        Protocol.REST,
        "https://ndxp.invalid",
        "kv/integ/cbccvc",
        direction,
        cron,
        true);
  }

  static Run run(UUID id, Kind kind, Status status, UUID retryOf) {
    return new Run(
        id,
        CBCCVC.id(),
        kind,
        status,
        retryOf,
        0,
        0,
        0,
        0,
        null,
        NOW.minusSeconds(600),
        status == Status.CHO ? null : NOW.minusSeconds(500),
        status.open() ? null : NOW.minusSeconds(100));
  }

  record Closed(UUID run, Status status, int dead, String reason) {}

  final Connectors connectors = mock(Connectors.class);
  final SyncRuns runs = mock(SyncRuns.class);
  final DeadLetterService deadLetters = mock(DeadLetterService.class);
  final PullService pulls = mock(PullService.class);
  final InboundPipeline pipeline = mock(InboundPipeline.class);
  final InboundConnector source = mock(InboundConnector.class);
  final List<Closed> closed = new ArrayList<>();
  final List<Duration> lags = new ArrayList<>();
  final SyncRunService service;

  SyncRunServiceTest() {
    when(source.code()).thenReturn("CBCCVC");
    when(connectors.findByCode(T, "CBCCVC")).thenReturn(Optional.of(CBCCVC));
    when(connectors.findById(T, CBCCVC.id())).thenReturn(Optional.of(CBCCVC));
    service =
        new SyncRunService(
            connectors,
            runs,
            deadLetters,
            pulls,
            pipeline,
            List.of(source),
            (t, code, run, status, dead, reason) ->
                closed.add(new Closed(run, status, dead, reason)),
            new SyncRunService.RunMetrics() {
              @Override
              public void cursorLag(String connectorCode, Duration lag) {
                lags.add(lag);
              }

              @Override
              public void finished(
                  String connectorCode, Status status, Duration elapsed, int messages) {}
            },
            TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SyncRunService.Settings(10, 2, Duration.ofMillis(1), 2, 60));
  }

  static InboundMessage page(String id) {
    return new InboundMessage(id, "JSON", new byte[] {1});
  }

  @Test
  void moLanChayNgayVaGuiLaiCungMaTraLanChayCu() {
    when(runs.find(T, RUN))
        .thenReturn(Optional.empty(), Optional.of(run(RUN, Kind.NGAY, Status.CHO, null)));
    when(runs.insert(T, RUN, CBCCVC.id(), Kind.NGAY, null, NOW)).thenReturn(true);
    assertThat(service.request(T, RUN, "CBCCVC", Kind.NGAY).status()).isEqualTo(Status.CHO);
    assertThat(service.request(T, RUN, "CBCCVC", Kind.NGAY).id()).isEqualTo(RUN);
    verify(runs, times(1)).insert(any(), any(), any(), any(), any(), any());
  }

  @Test
  void tuChoiKetNoiKhongChayTheoLanChuaCauHinhHoacDangChay() {
    assertThatThrownBy(() -> service.request(T, RUN, "SCIM", Kind.NGAY))
        .isInstanceOfSatisfying(
            SyncRunRejectedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(SyncRunRejectedException.Reason.CONNECTOR_NOT_RUNNABLE));
    assertThatThrownBy(() -> service.request(T, RUN, "CBCCVC", Kind.CHAY_LAI))
        .isInstanceOf(IllegalArgumentException.class);
    when(runs.find(T, RUN)).thenReturn(Optional.empty());
    when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.request(T, RUN, "CBCCVC", Kind.NGAY))
        .isInstanceOfSatisfying(
            SyncRunRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(SyncRunRejectedException.Reason.RUN_IN_PROGRESS));
    Run other =
        new Run(
            RUN, UUID.randomUUID(), Kind.NGAY, Status.CHO, null, 0, 0, 0, 0, null, NOW, null, null);
    when(runs.find(T, RUN)).thenReturn(Optional.of(other));
    assertThatThrownBy(() -> service.request(T, RUN, "CBCCVC", Kind.NGAY))
        .isInstanceOf(IllegalArgumentException.class);
    when(connectors.findByCode(T, "CBCCVC"))
        .thenReturn(Optional.of(connector("CBCCVC", Direction.RA, null)));
    assertThatThrownBy(() -> service.request(T, RUN, "CBCCVC", Kind.NGAY))
        .isInstanceOfSatisfying(
            SyncRunRejectedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(SyncRunRejectedException.Reason.CONNECTOR_NOT_CONFIGURED));
  }

  @Test
  void chayLaiChiKhiLanGocDaKetThuc() {
    UUID retry = UUID.randomUUID();
    when(runs.find(T, RUN)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.retry(T, RUN, retry, "CBCCVC"))
        .isInstanceOf(java.util.NoSuchElementException.class);
    when(runs.find(T, RUN)).thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.DANG_CHAY, null)));
    assertThatThrownBy(() -> service.retry(T, RUN, retry, "CBCCVC"))
        .isInstanceOfSatisfying(
            SyncRunRejectedException.class,
            e ->
                assertThat(e.reason()).isEqualTo(SyncRunRejectedException.Reason.RUN_NOT_FINISHED));
    when(runs.find(T, RUN)).thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.LOI, null)));
    when(runs.find(T, retry))
        .thenReturn(Optional.empty(), Optional.of(run(retry, Kind.CHAY_LAI, Status.CHO, RUN)));
    when(runs.insert(T, retry, CBCCVC.id(), Kind.CHAY_LAI, RUN, NOW)).thenReturn(true);
    assertThat(service.retry(T, RUN, retry, "CBCCVC").retryOf()).isEqualTo(RUN);
  }

  @Test
  void keoHetTrangDuaQuaLuongVaoRoiDongLanChay() throws Exception {
    when(runs.due(4, 60)).thenReturn(List.of(new Due(T.value(), RUN)));
    when(runs.claim(T, RUN, 60, NOW)).thenReturn(true);
    when(runs.find(T, RUN)).thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.DANG_CHAY, null)));
    Pull p1 = new Pull(T, CBCCVC, "c0");
    Pull p2 = new Pull(T, CBCCVC, "c1");
    when(pulls.prepare(eq(source), any(Active.class))).thenReturn(p1, p2);
    when(pulls.fetch(source, p1))
        .thenThrow(new IOException("503"))
        .thenReturn(new Page(List.of(page("a"), page("b"), page("c")), "c1", NOW.minusSeconds(30)));
    when(pulls.fetch(source, p2)).thenReturn(new Page(List.of(), "c1", null));
    when(pipeline.receive(T, "CBCCVC", page("a")))
        .thenReturn(new Receipt(Result.TRANSLATED, UUID.randomUUID(), List.of()));
    when(pipeline.receive(T, "CBCCVC", page("b")))
        .thenReturn(new Receipt(Result.DUPLICATE, null, List.of()));
    UUID inbox = UUID.randomUUID();
    when(pipeline.receive(T, "CBCCVC", page("c")))
        .thenThrow(
            new InboundProcessingException(
                T, CBCCVC.id(), inbox, new IllegalStateException("org")));
    when(pipeline.reprocess(T, inbox))
        .thenThrow(
            new InboundProcessingException(
                T, CBCCVC.id(), inbox, new IllegalStateException("org")));
    Letter letter =
        new Letter(UUID.randomUUID(), CBCCVC.id(), "VAO", inbox, "X", "x", NOW, null, null);
    when(deadLetters.list(eq(T), eq(CBCCVC.id()), eq(true), any(), any(), anyInt()))
        .thenReturn(List.of(letter));

    assertThat(service.runDue(4)).isEqualTo(1);

    verify(pulls).advance(eq(source), eq(p1), any());
    verify(deadLetters).record(eq(T), eq(CBCCVC.id()), eq("VAO"), eq(inbox), any());
    verify(runs, times(2)).progress(T, RUN, 2, 3, 1, NOW);
    verify(runs).finish(T, RUN, Status.XONG, 1, null, NOW);
    assertThat(closed).containsExactly(new Closed(RUN, Status.XONG, 1, null));
    assertThat(lags).containsExactly(Duration.ofSeconds(30));
  }

  @Test
  void nguonLoiHetLuotThiLanChayLoiVaGiuConTro() throws Exception {
    when(runs.claim(T, RUN, 60, NOW)).thenReturn(true);
    when(runs.find(T, RUN)).thenReturn(Optional.of(run(RUN, Kind.LICH, Status.DANG_CHAY, null)));
    Pull p = new Pull(T, CBCCVC, "c0");
    when(pulls.prepare(eq(source), any(Active.class))).thenReturn(p);
    when(pulls.fetch(source, p)).thenThrow(new IOException("503"));
    when(deadLetters.list(any(), any(), anyBoolean(), any(), any(), anyInt()))
        .thenReturn(List.of());
    assertThat(service.execute(T, RUN)).isTrue();
    verify(pulls, never()).advance(any(), any(), any());
    verify(runs).finish(T, RUN, Status.LOI, 0, "IOException", NOW);
    assertThat(closed)
        .singleElement()
        .satisfies(c -> assertThat(c.reason()).contains("IOException"));
    when(runs.claim(T, RUN, 60, NOW)).thenReturn(false);
    assertThat(service.execute(T, RUN)).isFalse();
  }

  @Test
  void chayLaiThuChetCuaLanGoc() {
    UUID retry = UUID.randomUUID();
    when(runs.claim(T, retry, 60, NOW)).thenReturn(true);
    when(runs.find(T, retry))
        .thenReturn(Optional.of(run(retry, Kind.CHAY_LAI, Status.DANG_CHAY, RUN)));
    when(runs.find(T, RUN)).thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.XONG, null)));
    Letter inWindow =
        new Letter(
            UUID.randomUUID(),
            CBCCVC.id(),
            "VAO",
            UUID.randomUUID(),
            "X",
            "x",
            NOW.minusSeconds(300),
            null,
            null);
    Letter failing =
        new Letter(
            UUID.randomUUID(),
            CBCCVC.id(),
            "VAO",
            UUID.randomUUID(),
            "X",
            "x",
            NOW.minusSeconds(200),
            null,
            null);
    Letter outbound =
        new Letter(
            UUID.randomUUID(),
            CBCCVC.id(),
            "RA",
            UUID.randomUUID(),
            "X",
            "x",
            NOW.minusSeconds(200),
            null,
            null);
    Letter old =
        new Letter(
            UUID.randomUUID(),
            CBCCVC.id(),
            "VAO",
            UUID.randomUUID(),
            "X",
            "x",
            NOW.minusSeconds(9000),
            null,
            null);
    when(deadLetters.list(eq(T), eq(CBCCVC.id()), eq(true), any(), any(), anyInt()))
        .thenReturn(List.of(old, inWindow, failing, outbound));
    org.mockito.Mockito.doThrow(new IllegalStateException("vẫn lỗi"))
        .when(deadLetters)
        .replay(T, failing.id(), SyncRunService.SYSTEM_ACTOR);
    assertThat(service.execute(T, retry)).isTrue();
    verify(deadLetters).replay(T, inWindow.id(), SyncRunService.SYSTEM_ACTOR);
    verify(deadLetters, never()).replay(T, old.id(), SyncRunService.SYSTEM_ACTOR);
    verify(runs).finish(T, retry, Status.XONG, 1, null, NOW);
  }

  @Test
  void lichDemMoLanChayKhiDenGio() {
    when(connectors.enabled())
        .thenReturn(
            List.of(
                new Active(T.value(), CBCCVC.id(), "CBCCVC"),
                new Active(T.value(), UUID.randomUUID(), "VOFFICE")));
    when(runs.lastRequested(T, CBCCVC.id(), Kind.LICH)).thenReturn(Optional.empty());
    when(runs.find(any(), any()))
        .thenReturn(Optional.empty(), Optional.of(run(RUN, Kind.LICH, Status.CHO, null)));
    when(runs.insert(eq(T), any(), eq(CBCCVC.id()), eq(Kind.LICH), any(), eq(NOW)))
        .thenReturn(true);
    assertThat(service.scheduleDue()).isEqualTo(1);
    // 01:00 giờ Việt Nam ngày 24/10 là 18:00 UTC ngày 23/10, đã chạy lúc đó thì chưa đến giờ.
    when(runs.lastRequested(T, CBCCVC.id(), Kind.LICH))
        .thenReturn(Optional.of(Instant.parse("2026-10-23T18:00:00Z")));
    assertThat(service.scheduleDue()).isZero();
    when(connectors.findById(T, CBCCVC.id()))
        .thenReturn(Optional.of(connector("CBCCVC", Direction.VAO, "sai lịch")));
    assertThat(service.scheduleDue()).isZero();
  }

  @Test
  void thamSoVaLanChayDangChay() {
    assertThatThrownBy(() -> new SyncRunService.Settings(0, 1, null, 1, 60))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new SyncRunService.Settings(1, 1, null, 1, 60).fetchBackoff()).isPositive();
    when(runs.open(T, CBCCVC.id()))
        .thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.DANG_CHAY, null)));
    assertThat(SyncRunService.runningRun(runs, T, CBCCVC.id())).contains(RUN);
    when(runs.open(T, CBCCVC.id())).thenReturn(Optional.of(run(RUN, Kind.NGAY, Status.CHO, null)));
    assertThat(SyncRunService.runningRun(runs, T, CBCCVC.id())).isEmpty();
    when(runs.due(anyInt(), anyInt())).thenReturn(List.of(new Due(T.value(), RUN)));
    when(runs.claim(any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("db"));
    assertThat(service.runDue(1)).isZero();
  }
}
