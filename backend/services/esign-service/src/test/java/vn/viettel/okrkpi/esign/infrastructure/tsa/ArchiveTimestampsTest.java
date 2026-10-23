package vn.viettel.okrkpi.esign.infrastructure.tsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/** Dấu thời gian tài liệu cho PAdES-B-LTA (DV-10-03). */
class ArchiveTimestampsTest {

  static TsaMockServer tsa;

  @BeforeAll
  static void start() throws Exception {
    tsa = new TsaMockServer();
  }

  @AfterAll
  static void stop() {
    tsa.close();
  }

  static byte[] blankPdf() throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void themDauThoiGianTaiLieuVaDocHanChungThuTsa() throws Exception {
    byte[] pdf = blankPdf();
    assertThat(ArchiveTimestamps.latestTsaExpiry(pdf)).isEmpty();
    byte[] once = ArchiveTimestamps.addDocumentTimestamp(pdf, tsa.client(), Calendar.getInstance());
    byte[] twice =
        ArchiveTimestamps.addDocumentTimestamp(once, tsa.client(), Calendar.getInstance());
    Instant expiry = tsa.tsaCert.getNotAfter().toInstant();
    assertThat(ArchiveTimestamps.latestTsaExpiry(twice)).contains(expiry);
    try (PDDocument doc = Loader.loadPDF(twice)) {
      List<PDSignature> sigs = doc.getSignatureDictionaries();
      assertThat(sigs).hasSize(2);
      PDSignature last = sigs.get(1);
      assertThat(last.getSubFilter()).isEqualTo("ETSI.RFC3161");
      TimeStampToken token = new TimeStampToken(new CMSSignedData(last.getContents()));
      assertThat(token.getTimeStampInfo().getMessageImprintDigest())
          .isEqualTo(Hashes.sha256(last.getSignedContent(twice)));
    }
  }

  @Test
  void tepKhongPhaiPdfBiTuChoi() {
    assertThatThrownBy(() -> ArchiveTimestamps.latestTsaExpiry(new byte[] {1, 2}))
        .isInstanceOf(SignatureProviderException.class);
    assertThatThrownBy(
            () ->
                ArchiveTimestamps.addDocumentTimestamp(
                    new byte[] {1}, tsa.client(), Calendar.getInstance()))
        .isInstanceOf(SignatureProviderException.class);
  }

  @Test
  void khoaDoiTuongMoiThayHauToCu() {
    Instant now = Instant.parse("2027-01-15T02:00:00Z");
    assertThat(ArchiveTimestampRenewalJob.renewedKey("t/a/b.pdf", now))
        .isEqualTo("t/a/b.lta-20270115.pdf");
    assertThat(ArchiveTimestampRenewalJob.renewedKey("t/a/b.lta-20260115.pdf", now))
        .isEqualTo("t/a/b.lta-20270115.pdf");
    assertThat(ArchiveTimestampRenewalJob.renewedKey("t/a/b", now))
        .isEqualTo("t/a/b.lta-20270115.pdf");
  }
}
