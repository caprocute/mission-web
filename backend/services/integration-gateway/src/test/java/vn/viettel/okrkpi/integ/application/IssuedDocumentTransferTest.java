package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSearch.Target;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.IssuedDocument;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource.OriginalFile;
import vn.viettel.okrkpi.integ.application.port.EvidenceObjects;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Chuyển tệp gốc văn bản đã ban hành sang kho minh chứng (CORE-03-04, BR-07-13): chỉ chuyển khi chữ
 * ký gốc hợp lệ và tệp trùng bản đã ký; văn bản không thuộc đơn vị là rỗng; lỗi vào ra để bên gọi
 * thử lại.
 */
class IssuedDocumentTransferTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID UNIT = UUID.randomUUID();
  static final UUID EVIDENCE = UUID.randomUUID();
  static final UUID PERSON = UUID.randomUUID();
  static final byte[] CONTENT = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

  private IssuedDocumentSearch search;
  private IssuedDocumentSource source;
  private EvidenceObjects evidence;
  private Connector connector;

  @BeforeEach
  void setUp() {
    search = mock(IssuedDocumentSearch.class);
    source = mock(IssuedDocumentSource.class);
    evidence = mock(EvidenceObjects.class);
    connector =
        new Connector(
            UUID.randomUUID(),
            TENANT.value(),
            "VOFFICE",
            Protocol.REST,
            "http://voffice.invalid",
            null,
            Direction.HAI_CHIEU,
            null,
            true);
    when(search.target(TENANT, "VOFFICE", UNIT))
        .thenReturn(Optional.of(new Target(connector, source, "DV-01")));
  }

  private static OriginalFile file(boolean valid, byte[] signed) {
    return new OriginalFile(
        new IssuedDocument("47-BC", "47-BC", LocalDate.of(2026, 10, 19), "Báo cáo", "Bình", valid),
        CONTENT,
        "application/pdf",
        "47-BC.pdf",
        signed);
  }

  private static byte[] sha(byte[] b) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(b);
  }

  private Optional<IssuedDocumentTransfer.Transfer> run(IssuedDocumentTransfer t) {
    return t.transfer(TENANT, "VOFFICE", UNIT, "47-BC", EVIDENCE, PERSON, "k-1");
  }

  @Test
  void chuyenKhiChuKyHopLeVaTepTrungBanDaKy() throws Exception {
    when(source.fetch(connector, "DV-01", "47-BC"))
        .thenReturn(Optional.of(file(true, sha(CONTENT))));
    EvidenceObjects.Stored stored =
        new EvidenceObjects.Stored(
            UUID.randomUUID(), 1, sha(CONTENT), CONTENT.length, "application/pdf");
    when(evidence.ingest(TENANT, "k-1", EVIDENCE, PERSON, "47-BC.pdf", "application/pdf", CONTENT))
        .thenReturn(stored);

    IssuedDocumentTransfer.Transfer t =
        run(new IssuedDocumentTransfer(search, evidence)).orElseThrow();
    assertThat(t.signatureValid()).isTrue();
    assertThat(t.stored()).isEqualTo(stored);
  }

  @Test
  void chuKySaiHoacTepBiSuaKhongChuyen() throws Exception {
    IssuedDocumentTransfer transfer = new IssuedDocumentTransfer(search, evidence);
    when(source.fetch(connector, "DV-01", "47-BC"))
        .thenReturn(Optional.of(file(false, sha(CONTENT))));
    assertThat(run(transfer).orElseThrow().signatureValid()).isFalse();

    when(source.fetch(connector, "DV-01", "47-BC"))
        .thenReturn(Optional.of(file(true, new byte[32])));
    assertThat(run(transfer).orElseThrow().stored()).isNull();

    when(source.fetch(connector, "DV-01", "47-BC")).thenReturn(Optional.of(file(true, null)));
    assertThat(run(transfer).orElseThrow().signatureValid()).isFalse();
    verify(evidence, never()).ingest(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void khongThuocDonViHoacLoiVaoRa() throws Exception {
    IssuedDocumentTransfer transfer = new IssuedDocumentTransfer(search, evidence);
    when(source.fetch(connector, "DV-01", "47-BC")).thenReturn(Optional.empty());
    assertThat(run(transfer)).isEmpty();

    when(search.target(TENANT, "VOFFICE", UNIT)).thenReturn(Optional.empty());
    assertThat(run(transfer)).isEmpty();

    when(search.target(TENANT, "VOFFICE", UNIT))
        .thenReturn(Optional.of(new Target(connector, source, "DV-01")));
    when(source.fetch(connector, "DV-01", "47-BC")).thenThrow(new IOException("vOffice lỗi"));
    assertThatThrownBy(() -> run(transfer)).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void chuaCauHinhKhoMinhChungHoacThamSoSai() throws Exception {
    when(source.fetch(connector, "DV-01", "47-BC"))
        .thenReturn(Optional.of(file(true, sha(CONTENT))));
    assertThatThrownBy(() -> run(new IssuedDocumentTransfer(search, null)))
        .isInstanceOf(UncheckedIOException.class);
    IssuedDocumentTransfer transfer = new IssuedDocumentTransfer(search, evidence);
    assertThatThrownBy(() -> transfer.transfer(TENANT, "VOFFICE", UNIT, " ", EVIDENCE, PERSON, "k"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> transfer.transfer(TENANT, "VOFFICE", UNIT, "47-BC", EVIDENCE, PERSON, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
