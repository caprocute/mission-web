package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/**
 * Chuẩn bị và hoàn tất chữ ký PAdES trên PDF bằng PDFBox (Apache 2.0). Bước chuẩn bị thêm từ điển
 * chữ ký {@code ETSI.CAdES.detached} theo kiểu cập nhật tăng dần (tệp gốc giữ nguyên từng byte ở
 * đầu tệp), chừa vùng {@code /Contents} và tính băm SHA-256 của hai đoạn {@code /ByteRange}. Bước
 * hoàn tất chỉ ghi CMS (hex) vào vùng đã chừa, nên mọi byte ngoài vùng ký không đổi và hai bước có
 * thể chạy ở hai bản sao khác nhau của service.
 */
public final class PdfSignatures {

  private static final byte[] BYTE_RANGE = "/ByteRange".getBytes(StandardCharsets.US_ASCII);

  private PdfSignatures() {}

  /** PDF đã chừa vùng ký và băm vùng được ký. */
  public record Prepared(byte[] pdf, byte[] contentDigest) {}

  public static Prepared prepare(byte[] pdf, Calendar signDate, int signatureSize) {
    try (PDDocument doc = Loader.loadPDF(pdf);
        SignatureOptions options = new SignatureOptions()) {
      PDSignature signature = new PDSignature();
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
      signature.setSignDate(signDate);
      options.setPreferredSignatureSize(signatureSize);
      doc.addSignature(signature, options);
      ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + signatureSize * 2 + 4096);
      ExternalSigningSupport external = doc.saveIncrementalForExternalSigning(out);
      byte[] digest;
      try (InputStream content = external.getContent()) {
        digest = Hashes.sha256(content.readAllBytes());
      }
      external.setSignature(new byte[0]);
      return new Prepared(out.toByteArray(), digest);
    } catch (IOException e) {
      throw new SignatureProviderException(
          "Tệp PDF không đọc được để ký: " + e.getMessage(), true, e);
    }
  }

  /** Ghi CMS vào vùng {@code /Contents} đã chừa của {@code prepared}. */
  public static byte[] embed(byte[] prepared, byte[] cms) {
    long[] range = byteRange(prepared);
    int gapStart = (int) range[1];
    int gapEnd = (int) range[2];
    if (prepared[gapStart] != '<' || prepared[gapEnd - 1] != '>') {
      throw new IllegalStateException("Vùng /Contents không đúng vị trí /ByteRange");
    }
    byte[] hex = HexFormat.of().withUpperCase().formatHex(cms).getBytes(StandardCharsets.US_ASCII);
    if (hex.length > gapEnd - gapStart - 2) {
      throw new SignatureProviderException(
          "Chữ ký (" + cms.length + " byte) vượt vùng đã chừa trong PDF", true);
    }
    byte[] out = prepared.clone();
    System.arraycopy(hex, 0, out, gapStart + 1, hex.length);
    return out;
  }

  /** {@code /ByteRange [a b c d]} của chữ ký cuối cùng trong tệp. */
  static long[] byteRange(byte[] pdf) {
    int at = lastIndexOf(pdf, BYTE_RANGE);
    if (at < 0) {
      throw new IllegalStateException("PDF không có /ByteRange");
    }
    int i = at + BYTE_RANGE.length;
    while (i < pdf.length && pdf[i] != '[') {
      i++;
    }
    long[] values = new long[4];
    int n = 0;
    long current = -1;
    for (i = i + 1; i < pdf.length && n < 4; i++) {
      byte b = pdf[i];
      if (b >= '0' && b <= '9') {
        current = (current < 0 ? 0 : current * 10) + (b - '0');
      } else if (current >= 0) {
        values[n++] = current;
        current = -1;
      }
      if (b == ']') {
        break;
      }
    }
    if (n != 4) {
      throw new IllegalStateException("/ByteRange không hợp lệ");
    }
    return values;
  }

  private static int lastIndexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = data.length - pattern.length; i >= 0; i--) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
