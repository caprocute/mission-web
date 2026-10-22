package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import vn.viettel.okrkpi.esign.application.sign.Hashes;

/** Dựng PDF thử và kiểm chứng PDF đã ký (Bouncy Castle và, nếu có, {@code pdfsig} của Poppler). */
final class SignedPdfs {

  private SignedPdfs() {}

  static byte[] sample(String title) throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
        content.newLineAtOffset(72, 700);
        content.showText(title);
        content.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  /**
   * Chữ ký hợp lệ với {@code signer}, có dấu thời gian của {@code tsa} trên giá trị chữ ký, và tệp
   * gốc là tiền tố nguyên vẹn của tệp đã ký (không sửa ngoài vùng ký).
   */
  static void assertValid(
      byte[] original, byte[] signed, X509Certificate signer, X509Certificate tsa)
      throws Exception {
    assertThat(Arrays.copyOf(signed, original.length)).isEqualTo(original);
    try (PDDocument doc = Loader.loadPDF(signed)) {
      PDSignature sig = doc.getLastSignatureDictionary();
      assertThat(sig.getSubFilter()).isEqualTo(PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName());
      int[] range = sig.getByteRange();
      assertThat(range[0]).isZero();
      assertThat((long) range[2] + range[3]).isEqualTo(signed.length);
      CMSSignedData cms =
          new CMSSignedData(
              new CMSProcessableByteArray(sig.getSignedContent(signed)), sig.getContents(signed));
      SignerInformation info = cms.getSignerInfos().getSigners().iterator().next();
      assertThat(info.verify(new JcaSimpleSignerInfoVerifierBuilder().build(signer))).isTrue();
      if (tsa != null) {
        var attr =
            info.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        assertThat(attr).isNotNull();
        TimeStampToken token =
            new TimeStampToken(
                new CMSSignedData(
                    attr.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded()));
        token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(tsa));
        assertThat(token.getTimeStampInfo().getMessageImprintDigest())
            .isEqualTo(Hashes.sha256(info.getSignature()));
      }
    }
    pdfsig(signed);
  }

  /** {@code pdfsig -nocert}: công cụ độc lập xác nhận chữ ký toàn vẹn (bỏ qua nếu máy không có). */
  static void pdfsig(byte[] signed) throws Exception {
    Path bin = Path.of("/usr/bin/pdfsig");
    if (!Files.isExecutable(bin)) {
      return;
    }
    Path file = Files.createTempFile("da-ky-", ".pdf");
    try {
      Files.write(file, signed);
      Process p =
          new ProcessBuilder(bin.toString(), "-nocert", "-no-ocsp", file.toString())
              .redirectErrorStream(true)
              .start();
      String out = new String(p.getInputStream().readAllBytes());
      assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
      assertThat(out).contains("Signature is Valid").contains("ETSI.CAdES.detached");
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
