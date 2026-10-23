package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.Connectors.Active;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.DeadLetters.Letter;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Raw;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Stored;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Mapping;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Due;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages.Row;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher.Sealed;
import vn.viettel.okrkpi.integ.application.port.SyncCursors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.InboxStatus;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Luồng vào, luồng ra, ánh xạ mã và lượt kéo với cổng giả lập (DV-13-01). */
class IntegPipelinesTest {

  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
  static final TenantId TENANT =
      TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-00000013a001"));
  static final String CODE = "MAU";
  static final Connector MAU =
      new Connector(
          UUID.randomUUID(),
          TENANT.value(),
          CODE,
          Protocol.REST,
          "https://doi-tac.invalid",
          "kv/integ/mau",
          Direction.HAI_CHIEU,
          null,
          true);

  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final TransactionOperations tx = TransactionOperations.withoutTransaction();
  final Connectors connectors = mock(Connectors.class);
  final InboxMessages inbox = mock(InboxMessages.class);
  final OutboundMessages messages = mock(OutboundMessages.class);
  final DeadLetters letters = mock(DeadLetters.class);
  final MappingXrefs xrefs = mock(MappingXrefs.class);
  final CanonicalEventSink sink = mock(CanonicalEventSink.class);
  final Translator translator = mock(Translator.class);
  final OutboundSender sender = mock(OutboundSender.class);
  final PayloadCipher cipher =
      new PayloadCipher() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain) {
          return new Sealed(plain.clone(), UUID.randomUUID());
        }

        @Override
        public byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext) {
          return ciphertext.clone();
        }
      };
  final MappingXrefService mappings = new MappingXrefService(xrefs);
  final InboundPipeline inbound =
      new InboundPipeline(
          connectors,
          inbox,
          letters,
          cipher,
          sink,
          mappings,
          new Translators(List.of(), Map.of(CODE, translator)),
          tx,
          clock,
          7);

  {
    when(connectors.findByCode(TENANT, CODE)).thenReturn(Optional.of(MAU));
    when(connectors.findById(TENANT, MAU.id())).thenReturn(Optional.of(MAU));
    when(sender.connectorCode()).thenReturn(CODE);
  }

  final OutboundPipeline outbound =
      new OutboundPipeline(
          connectors,
          messages,
          inbox,
          letters,
          cipher,
          List.of(sender),
          new RetrySchedule(Duration.ofSeconds(2), 2, Duration.ofMinutes(1), 3),
          Duration.ofMinutes(10),
          tx,
          clock);

  static InboundMessage message(String id) {
    return new InboundMessage(id, "VAN_BAN", new byte[] {1, 2});
  }

  static CanonicalEvent event() {
    return new CanonicalEvent(
        "integ.document.received.v1",
        "VanBan",
        UUID.randomUUID(),
        1,
        mock(SpecificRecord.class),
        null);
  }

  @Test
  void luongVaoPhatSuKienChongTrungVaGhiLoi() {
    UUID eventId = UUID.randomUUID();
    when(inbox.store(any(), any(), eq(TODAY), eq(TODAY.minusDays(7)), any(), any(), any(), any()))
        .thenAnswer(i -> new Stored(i.getArgument(1), TODAY, InboxStatus.MOI, true));
    when(translator.translate(any(), any())).thenReturn(List.of(event()));
    when(sink.publish(eq(TENANT), any())).thenReturn(eventId);

    var published = inbound.receive(TENANT, CODE, message("VB-1"));
    assertThat(published.result()).isEqualTo(InboundPipeline.Result.PUBLISHED);
    assertThat(published.eventIds()).containsExactly(eventId);
    verify(inbox).mark(TENANT, TODAY, published.inboxId(), InboxStatus.DA_PHAT, eventId);

    when(translator.translate(any(), any())).thenReturn(null);
    assertThat(inbound.receive(TENANT, CODE, message("VB-2")).result())
        .isEqualTo(InboundPipeline.Result.TRANSLATED);

    when(inbox.processed(TENANT, MAU.id(), "VB-1", TODAY.minusDays(7))).thenReturn(true);
    assertThat(inbound.receive(TENANT, CODE, message("VB-1")).result())
        .isEqualTo(InboundPipeline.Result.DUPLICATE);
    assertThat(inbound.processed(TENANT, CODE, "VB-1")).isTrue();
    assertThat(inbound.processed(TENANT, "KHAC", "VB-1")).isFalse();

    UUID existing = UUID.randomUUID();
    when(inbox.store(any(), any(), any(), any(), any(), eq("VB-3"), any(), any()))
        .thenReturn(new Stored(existing, TODAY, InboxStatus.DA_CHUYEN_DOI, false));
    var dup = inbound.receive(TENANT, CODE, message("VB-3"));
    assertThat(dup.result()).isEqualTo(InboundPipeline.Result.DUPLICATE);
    assertThat(dup.inboxId()).isEqualTo(existing);

    when(translator.translate(any(), any())).thenThrow(new IllegalArgumentException("hỏng"));
    assertThatThrownBy(() -> inbound.receive(TENANT, CODE, message("VB-4")))
        .isInstanceOfSatisfying(
            InboundProcessingException.class,
            e -> {
              assertThat(e.tenant()).isEqualTo(TENANT);
              assertThat(e.connectorId()).isEqualTo(MAU.id());
              assertThat(e.inboxId()).isNotNull();
            });
    verify(inbox).mark(eq(TENANT), eq(TODAY), any(), eq(InboxStatus.LOI), isNull());
  }

  @Test
  void luongVaoTuChoiKetNoiKhongHopLe() {
    when(connectors.findByCode(TENANT, "TAT"))
        .thenReturn(
            Optional.of(
                new Connector(
                    UUID.randomUUID(),
                    TENANT.value(),
                    "TAT",
                    Protocol.SFTP,
                    "sftp://x.invalid",
                    "kv/x",
                    Direction.RA,
                    null,
                    true)));
    assertThatThrownBy(() -> inbound.receive(TENANT, "KHAC", message("A")))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> inbound.receive(TENANT, "TAT", message("A")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new InboundPipeline(
                    connectors,
                    inbox,
                    letters,
                    cipher,
                    sink,
                    mappings,
                    new Translators(List.of(), Map.of()),
                    tx,
                    clock,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Translators(List.of(), Map.of()).require(CODE))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void xuLyLaiGiaiMaNguyenVan() {
    UUID inboxId = UUID.randomUUID();
    when(inbox.find(TENANT, inboxId))
        .thenReturn(
            Optional.of(
                new Raw(
                    inboxId, TODAY, MAU.id(), "VB-9", "VAN_BAN", new byte[] {7}, InboxStatus.LOI)));
    when(translator.translate(any(), any()))
        .thenAnswer(
            i -> {
              InboundMessage m = i.getArgument(1);
              assertThat(m.raw()).containsExactly(7);
              return List.of();
            });
    assertThat(inbound.reprocess(TENANT, inboxId).result())
        .isEqualTo(InboundPipeline.Result.TRANSLATED);

    UUID done = UUID.randomUUID();
    when(inbox.find(TENANT, done))
        .thenReturn(
            Optional.of(
                new Raw(
                    done,
                    TODAY,
                    MAU.id(),
                    "VB-8",
                    "VAN_BAN",
                    new byte[] {7},
                    InboxStatus.DA_PHAT)));
    assertThat(inbound.reprocess(TENANT, done).result())
        .isEqualTo(InboundPipeline.Result.DUPLICATE);
    assertThatThrownBy(() -> inbound.reprocess(TENANT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void anhXaMaNgoaiTaoMoiDocLaiVaDoiHieuLuc() {
    UUID internal = UUID.randomUUID();
    Mapping old = new Mapping(UUID.randomUUID(), "DV-1", internal, TODAY.minusDays(3));
    when(xrefs.byExternal(TENANT, MAU.id(), EntityType.DON_VI, "DV-1", TODAY))
        .thenReturn(Optional.of(old));
    when(xrefs.byInternal(TENANT, MAU.id(), EntityType.DON_VI, internal, TODAY))
        .thenReturn(Optional.of(old));
    assertThat(mappings.resolve(TENANT, MAU.id(), EntityType.DON_VI, "DV-1", TODAY))
        .contains(internal);
    assertThat(mappings.externalOf(TENANT, MAU.id(), EntityType.DON_VI, internal, TODAY))
        .contains("DV-1");
    assertThat(
            new TranslationContext(TENANT, MAU, TODAY, mappings)
                .internalId(EntityType.DON_VI, "DV-1"))
        .isEqualTo(internal);

    // Tạo mới; nếu tranh chấp thì đọc lại bản đã có.
    when(xrefs.insert(any(), any(), any(), any(), eq("DV-2"), any(), any(), any()))
        .thenReturn(true);
    assertThat(mappings.resolveOrCreate(TENANT, MAU.id(), EntityType.DON_VI, "DV-2", TODAY, " "))
        .isNotNull();
    UUID raced = UUID.randomUUID();
    when(xrefs.byExternal(TENANT, MAU.id(), EntityType.DON_VI, "DV-3", TODAY))
        .thenReturn(
            Optional.empty(), Optional.of(new Mapping(UUID.randomUUID(), "DV-3", raced, TODAY)));
    assertThat(
            mappings.resolveOrCreate(
                TENANT, MAU.id(), EntityType.DON_VI, "DV-3", TODAY, "x".repeat(300)))
        .isEqualTo(raced);
    assertThatThrownBy(
            () ->
                mappings.resolveOrCreate(TENANT, MAU.id(), EntityType.DON_VI, "DV-4", TODAY, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> mappings.resolveOrCreate(TENANT, MAU.id(), EntityType.DON_VI, "", TODAY, null))
        .isInstanceOf(IllegalArgumentException.class);

    // Gán lại: đóng bản cũ rồi chèn bản mới; cùng mã nội bộ thì không đổi.
    assertThat(mappings.link(TENANT, MAU.id(), EntityType.DON_VI, "DV-1", internal, TODAY, null))
        .isFalse();
    UUID other = UUID.randomUUID();
    when(xrefs.insert(any(), any(), any(), any(), eq("DV-1"), eq(other), any(), any()))
        .thenReturn(true);
    assertThat(mappings.link(TENANT, MAU.id(), EntityType.DON_VI, "DV-1", other, TODAY, "đổi"))
        .isTrue();
    verify(xrefs).close(TENANT, old.id(), TODAY);
    when(xrefs.byExternal(TENANT, MAU.id(), EntityType.DON_VI, "DV-5", TODAY))
        .thenReturn(Optional.of(new Mapping(UUID.randomUUID(), "DV-5", internal, TODAY)));
    assertThatThrownBy(
            () -> mappings.link(TENANT, MAU.id(), EntityType.DON_VI, "DV-5", other, TODAY, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> mappings.link(TENANT, MAU.id(), EntityType.DON_VI, "DV-6", other, TODAY, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void luongRaChongVongLapChongTrungVaXepHang() {
    UUID echo = UUID.randomUUID();
    when(inbox.emitted(TENANT, MAU.id(), echo)).thenReturn(true);
    assertThat(outbound.enqueue(TENANT, CODE, echo, "KQ", new byte[] {1}).result())
        .isEqualTo(OutboundPipeline.Result.ECHO);
    UUID dup = UUID.randomUUID();
    when(messages.exists(TENANT, MAU.id(), dup)).thenReturn(true);
    assertThat(outbound.enqueue(TENANT, CODE, dup, "KQ", new byte[] {1}).result())
        .isEqualTo(OutboundPipeline.Result.DUPLICATE);
    var queued = outbound.enqueue(TENANT, CODE, UUID.randomUUID(), "KQ", new byte[] {1});
    assertThat(queued.result()).isEqualTo(OutboundPipeline.Result.QUEUED);
    verify(messages)
        .insert(
            eq(TENANT), eq(queued.id()), eq(TODAY), eq(MAU.id()), any(), eq("KQ"), any(), eq(NOW));

    assertThatThrownBy(() -> outbound.enqueue(TENANT, CODE, UUID.randomUUID(), " ", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> outbound.enqueue(TENANT, "KHAC", UUID.randomUUID(), "KQ", new byte[0]))
        .isInstanceOf(NoSuchElementException.class);
    Connector vao =
        new Connector(
            UUID.randomUUID(),
            TENANT.value(),
            "VAO",
            Protocol.REST,
            "e",
            "s",
            Direction.VAO,
            null,
            true);
    when(connectors.findByCode(TENANT, "VAO")).thenReturn(Optional.of(vao));
    assertThatThrownBy(() -> outbound.enqueue(TENANT, "VAO", UUID.randomUUID(), "KQ", new byte[0]))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new OutboundPipeline(
                    connectors,
                    messages,
                    inbox,
                    letters,
                    cipher,
                    List.of(sender, sender),
                    RetrySchedule.DEFAULT,
                    Duration.ofMinutes(1),
                    tx,
                    clock))
        .isInstanceOf(IllegalStateException.class);
  }

  Row row(UUID id, int attempts, OutboundStatus status) {
    return new Row(
        id, TODAY, MAU.id(), UUID.randomUUID(), "KQ", new byte[] {5}, status, attempts, null);
  }

  @Test
  void luongRaGuiXacNhanChoXacNhanThuLaiVaThuChet() throws IOException {
    UUID ok = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, ok)).thenReturn(Optional.of(row(ok, 0, OutboundStatus.CHO)));
    when(sender.send(MAU, ok, "KQ", new byte[] {5})).thenReturn(Optional.of("ACK"));
    assertThat(outbound.dispatch(TENANT, TODAY, ok)).contains(OutboundStatus.DA_XAC_NHAN);
    verify(messages).update(TENANT, TODAY, ok, OutboundStatus.DA_XAC_NHAN, 1, null, "ACK");

    UUID later = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, later))
        .thenReturn(Optional.of(row(later, 0, OutboundStatus.CHO)));
    when(sender.send(eq(MAU), eq(later), any(), any())).thenReturn(Optional.empty());
    assertThat(outbound.dispatch(TENANT, TODAY, later)).contains(OutboundStatus.DA_GUI);
    verify(messages)
        .update(TENANT, TODAY, later, OutboundStatus.DA_GUI, 1, NOW.plusSeconds(600), null);

    UUID noAck = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, noAck))
        .thenReturn(Optional.of(row(noAck, 2, OutboundStatus.DA_GUI)));
    when(sender.send(eq(MAU), eq(noAck), any(), any())).thenReturn(Optional.empty());
    assertThat(outbound.dispatch(TENANT, TODAY, noAck)).contains(OutboundStatus.LOI);

    UUID fail = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, fail))
        .thenReturn(
            Optional.of(row(fail, 0, OutboundStatus.CHO)),
            Optional.of(row(fail, 2, OutboundStatus.CHO)));
    when(sender.send(eq(MAU), eq(fail), any(), any())).thenThrow(new IOException("mạng"));
    assertThat(outbound.dispatch(TENANT, TODAY, fail)).contains(OutboundStatus.CHO);
    verify(messages).update(TENANT, TODAY, fail, OutboundStatus.CHO, 1, NOW.plusSeconds(2), null);
    assertThat(outbound.dispatch(TENANT, TODAY, fail)).contains(OutboundStatus.LOI);
    verify(letters)
        .insert(
            eq(TENANT),
            any(),
            eq(MAU.id()),
            eq(DeadLetterService.OUTBOUND),
            eq(fail),
            eq("IOException"),
            eq("java.io.IOException"));

    assertThat(outbound.dispatch(TENANT, TODAY, UUID.randomUUID())).isEmpty();

    // Hàng đến hạn: một bản gửi được, một bản đã bị luồng khác giành, một bản lỗi bất ngờ.
    UUID boom = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, boom)).thenThrow(new IllegalStateException("x"));
    when(messages.due(10))
        .thenReturn(
            List.of(
                new Due(TENANT.value(), TODAY, ok),
                new Due(TENANT.value(), TODAY, UUID.randomUUID()),
                new Due(TENANT.value(), TODAY, boom)));
    assertThat(outbound.dispatchDue(10)).isEqualTo(1);
  }

  @Test
  void luongRaKhongCoAdapterVaoThuLai() {
    Connector khac =
        new Connector(
            UUID.randomUUID(),
            TENANT.value(),
            "KHAC",
            Protocol.SOAP,
            "e",
            "s",
            Direction.RA,
            null,
            true);
    when(connectors.findById(TENANT, khac.id())).thenReturn(Optional.of(khac));
    UUID id = UUID.randomUUID();
    when(messages.claim(TENANT, TODAY, id))
        .thenReturn(
            Optional.of(
                new Row(
                    id,
                    TODAY,
                    khac.id(),
                    UUID.randomUUID(),
                    "KQ",
                    new byte[0],
                    OutboundStatus.CHO,
                    0,
                    null)));
    assertThat(outbound.dispatch(TENANT, TODAY, id)).contains(OutboundStatus.CHO);
  }

  @Test
  void xacNhanSauVaDatLai() {
    UUID id = UUID.randomUUID();
    when(messages.find(TENANT, id)).thenReturn(Optional.of(row(id, 1, OutboundStatus.DA_GUI)));
    outbound.acknowledge(TENANT, id, "ACK-9");
    verify(messages).update(TENANT, TODAY, id, OutboundStatus.DA_XAC_NHAN, 1, null, "ACK-9");
    assertThatThrownBy(() -> outbound.acknowledge(TENANT, id, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> outbound.acknowledge(TENANT, UUID.randomUUID(), "A"))
        .isInstanceOf(NoSuchElementException.class);

    outbound.reset(TENANT, id);
    verify(messages).update(TENANT, TODAY, id, OutboundStatus.CHO, 0, NOW, null);
    UUID acked = UUID.randomUUID();
    when(messages.find(TENANT, acked))
        .thenReturn(Optional.of(row(acked, 1, OutboundStatus.DA_XAC_NHAN)));
    outbound.reset(TENANT, acked);
    outbound.acknowledge(TENANT, acked, "ACK-10");
    verify(messages, never()).update(eq(TENANT), any(), eq(acked), any(), anyInt(), any(), any());
  }

  @Test
  void phatLaiThuChetHaiChieu() {
    DeadLetterService service = new DeadLetterService(letters, inbound, outbound, tx, clock);
    UUID actor = UUID.randomUUID();
    UUID out = UUID.randomUUID();
    UUID outLetter = UUID.randomUUID();
    when(messages.find(TENANT, out)).thenReturn(Optional.of(row(out, 3, OutboundStatus.LOI)));
    when(letters.find(TENANT, outLetter))
        .thenReturn(
            Optional.of(
                new Letter(
                    outLetter,
                    MAU.id(),
                    DeadLetterService.OUTBOUND,
                    out,
                    "E",
                    "E",
                    NOW,
                    null,
                    null)));
    when(letters.resolve(TENANT, outLetter, actor, NOW)).thenReturn(true);
    service.replay(TENANT, outLetter, actor);
    verify(messages).update(TENANT, TODAY, out, OutboundStatus.CHO, 0, NOW, null);

    UUID inboxId = UUID.randomUUID();
    UUID inLetter = UUID.randomUUID();
    when(inbox.find(TENANT, inboxId))
        .thenReturn(
            Optional.of(
                new Raw(
                    inboxId,
                    TODAY,
                    MAU.id(),
                    "VB-7",
                    "VAN_BAN",
                    new byte[] {1},
                    InboxStatus.DA_PHAT)));
    when(letters.find(TENANT, inLetter))
        .thenReturn(
            Optional.of(
                new Letter(
                    inLetter,
                    MAU.id(),
                    DeadLetterService.INBOUND,
                    inboxId,
                    "E",
                    "E",
                    NOW,
                    null,
                    null)));
    when(letters.resolve(TENANT, inLetter, actor, NOW)).thenReturn(true);
    service.replay(TENANT, inLetter, actor);
    verify(letters).resolve(TENANT, inLetter, actor, NOW);

    when(letters.page(TENANT, MAU.id(), true, null, null, 50)).thenReturn(List.of());
    assertThat(service.list(TENANT, MAU.id(), true, null, null, 50)).isEmpty();
    assertThatThrownBy(() -> service.replay(TENANT, UUID.randomUUID(), actor))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void luotKeoChiTienConTroKhiTrangCoConTroMoi() throws IOException {
    SyncCursors store = mock(SyncCursors.class);
    when(store.find(TENANT, MAU.id(), "van-ban")).thenReturn(Optional.of("4"));
    PullService pulls = new PullService(connectors, new SyncCursorService(store, clock, tx), tx);
    InboundConnector source = mock(InboundConnector.class);
    when(source.code()).thenReturn(CODE);
    when(source.stream()).thenReturn("van-ban");
    when(source.initialCursor()).thenReturn("0");
    when(connectors.enabled())
        .thenReturn(
            List.of(
                new Active(TENANT.value(), MAU.id(), CODE),
                new Active(UUID.randomUUID(), UUID.randomUUID(), "KHAC")));

    List<Active> targets = pulls.targets(CODE);
    assertThat(targets).hasSize(1);
    PullService.Pull pull = pulls.prepare(source, targets.get(0));
    assertThat(pull.cursor()).isEqualTo("4");

    when(source.fetch(MAU, "4")).thenReturn(null);
    Page empty = pulls.fetch(source, pull);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isEqualTo("4");
    pulls.advance(source, pull, empty);
    pulls.advance(source, pull, new Page(null, null, null));
    verify(store, never()).save(any(), any(), any(), any(), any(), any());

    pulls.advance(source, pull, new Page(List.of(message("A")), "5", NOW.minusSeconds(30)));
    verify(store).save(TENANT, MAU.id(), "van-ban", "5", NOW, 30);
    assertThatThrownBy(
            () -> pulls.prepare(source, new Active(TENANT.value(), UUID.randomUUID(), CODE)))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void macDinhCuaBoKetNoiVao() {
    InboundConnector c =
        new InboundConnector() {
          @Override
          public String code() {
            return CODE;
          }

          @Override
          public String stream() {
            return "s";
          }

          @Override
          public Page fetch(Connector connector, String cursor) {
            return new Page(List.of(), cursor, null);
          }

          @Override
          public Translator translator() {
            return translator;
          }
        };
    assertThat(c.initialCursor()).isEqualTo("0");
    assertThat(c.period()).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.maxConcurrentCalls()).isEqualTo(4);
    assertThat(new Translators(List.of(c), Map.of()).require(CODE)).isSameAs(translator);
    assertThatThrownBy(() -> new Translators(List.of(c), Map.of(CODE, translator)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(event().classification()).isNotNull();
  }

  @Test
  void luongVaoTuChoiKhongThuLaiVaKhongGhiTrungThuChet() {
    when(inbox.store(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(i -> new Stored(i.getArgument(1), TODAY, InboxStatus.MOI, true));
    when(translator.translate(any(), any()))
        .thenThrow(new TranslationRejectedException("DON_VI_CHUA_ANH_XA", "Đơn vị DV-9"));
    var r = inbound.receive(TENANT, CODE, message("VB-R"));
    assertThat(r.result()).isEqualTo(InboundPipeline.Result.REJECTED);
    verify(letters)
        .insert(
            eq(TENANT),
            any(),
            eq(MAU.id()),
            eq(DeadLetterService.INBOUND),
            eq(r.inboxId()),
            eq("DON_VI_CHUA_ANH_XA"),
            eq("Đơn vị DV-9"));
    when(letters.hasOpen(eq(TENANT), any())).thenReturn(true);
    inbound.receive(TENANT, CODE, message("VB-S"));
    verify(letters, org.mockito.Mockito.times(1))
        .insert(any(), any(), any(), any(), any(), eq("DON_VI_CHUA_ANH_XA"), any());

    UUID inboxId = UUID.randomUUID();
    when(inbox.find(TENANT, inboxId))
        .thenReturn(
            Optional.of(
                new Raw(
                    inboxId, TODAY, MAU.id(), "VB-R", "VAN_BAN", new byte[] {1}, InboxStatus.LOI)));
    assertThatThrownBy(() -> inbound.reprocess(TENANT, inboxId))
        .isInstanceOf(TranslationRejectedException.class);
  }

  @Test
  void traCuuVanBanDaBanHanh() throws IOException {
    IssuedDocumentSource source = mock(IssuedDocumentSource.class);
    when(source.connectorCode()).thenReturn(CODE);
    IssuedDocumentSearch search =
        new IssuedDocumentSearch(connectors, mappings, List.of(source), tx, clock);
    UUID unit = UUID.randomUUID();
    when(xrefs.byInternal(TENANT, MAU.id(), EntityType.DON_VI, unit, TODAY))
        .thenReturn(Optional.of(new Mapping(UUID.randomUUID(), "DV-1", unit, TODAY)));
    var page =
        new IssuedDocumentSource.Result(
            List.of(
                new IssuedDocumentSource.IssuedDocument(
                    "R1", "47-BC", TODAY, "Báo cáo", "Người ký", true)),
            "c2");
    when(source.search(MAU, "DV-1", "bc", 20, null)).thenReturn(page);
    assertThat(search.search(TENANT, CODE, unit, "bc", 20, null)).isEqualTo(page);
    assertThat(search.search(TENANT, CODE, UUID.randomUUID(), null, 20, null).items()).isEmpty();
    assertThat(new IssuedDocumentSource.Result(null, null).items()).isEmpty();

    when(source.search(MAU, "DV-1", null, 5, "c2")).thenThrow(new IOException("mạng"));
    assertThatThrownBy(() -> search.search(TENANT, CODE, unit, null, 5, "c2"))
        .isInstanceOf(java.io.UncheckedIOException.class);
    assertThatThrownBy(() -> search.search(TENANT, CODE, unit, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> search.search(TENANT, CODE, unit, "x".repeat(201), 5, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> search.search(TENANT, "KHAC", unit, null, 5, null))
        .isInstanceOf(NoSuchElementException.class);
    IssuedDocumentSource other = mock(IssuedDocumentSource.class);
    when(other.connectorCode()).thenReturn("TAT");
    when(connectors.findByCode(TENANT, "TAT")).thenReturn(Optional.empty());
    IssuedDocumentSearch two =
        new IssuedDocumentSearch(connectors, mappings, List.of(source, other), tx, clock);
    assertThatThrownBy(() -> two.search(TENANT, "TAT", unit, null, 5, null))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(
            () ->
                new IssuedDocumentSearch(connectors, mappings, List.of(source, source), tx, clock))
        .isInstanceOf(IllegalStateException.class);
  }
}
