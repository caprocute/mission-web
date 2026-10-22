package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/** Chuẩn bị vùng ký, dựng CMS từ chữ ký thô và nhúng vào PDF (DV-10-02), không qua HTTP. */
class PdfSignaturesTest {

  private static CscMockServer csc;

  @BeforeAll
  static void start() throws Exception {
    csc = new CscMockServer();
  }

  @AfterAll
  static void stop() {
    csc.close();
  }

  @Test
  void hashSignedRemotelyEmbedsIntoPdfWithoutTouchingOriginalBytes() throws Exception {
    byte[] original = SignedPdfs.sample("Bien ban tham dinh");
    PdfSignatures.Prepared prepared =
        PdfSignatures.prepare(original, Calendar.getInstance(), 32_768);
    byte[] attrs = Cms.signedAttributes(prepared.contentDigest(), csc.signerCert);
    byte[] raw = CscMockServer.sign(csc.signerKey.getPrivate(), Hashes.sha256(attrs));
    byte[] cms = Cms.signedData(attrs, raw, List.of(csc.signerCert, csc.caCert), null);
    byte[] signed = PdfSignatures.embed(prepared.pdf(), cms);
    assertThat(signed).hasSameSizeAs(prepared.pdf());
    SignedPdfs.assertValid(original, signed, csc.signerCert, null);
  }

  @Test
  void brokenInputsAreRejected() {
    assertThatThrownBy(
            () -> PdfSignatures.prepare("khong phai pdf".getBytes(), Calendar.getInstance(), 8))
        .isInstanceOfSatisfying(
            SignatureProviderException.class, e -> assertThat(e.permanent()).isTrue());
    assertThatThrownBy(() -> PdfSignatures.byteRange("%PDF-1.7".getBytes()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void signatureLargerThanReservedSpaceIsRejected() throws Exception {
    PdfSignatures.Prepared prepared =
        PdfSignatures.prepare(SignedPdfs.sample("x"), Calendar.getInstance(), 64);
    assertThatThrownBy(() -> PdfSignatures.embed(prepared.pdf(), new byte[4096]))
        .isInstanceOf(SignatureProviderException.class);
  }
}
