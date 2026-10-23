package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghép tệp ZIP của gói hồ sơ theo luồng (CORE-06-08, SCR-1102 "Tải gói (ZIP và manifest)"): mỗi tệp
 * PDF/A đọc từ kho đối tượng và chép thẳng vào luồng ZIP qua bộ đệm 64 KB, băm SHA-256 trong lúc
 * chép để đối chiếu với băm đã ghi lúc sinh; bộ nhớ dùng không phụ thuộc kích thước gói.
 *
 * <p>Bố cục: {@code manifest.json} (đúng bản chuẩn hoá đã băm và ký), {@code manifest.json.p7s}
 * (chữ ký gói khi đã ký), {@code thong-tin-goi.json} (mã gói, phiên bản, băm manifest) và {@code
 * bieu-mau/<mã mẫu>/<mã biểu mẫu>.pdf} (suy luận, wiki trang 14).
 */
public final class PackageArchive {

  static final String MANIFEST = "manifest.json";
  static final String SIGNATURE = "manifest.json.p7s";
  static final String INFO = "thong-tin-goi.json";
  static final String FORMS_DIR = "bieu-mau/";
  private static final int BUFFER = 64 * 1024;

  private PackageArchive() {}

  /** Kết quả: số byte của tệp ZIP và số tệp biểu mẫu. */
  public record Result(long bytes, int files) {}

  /** Tệp trên kho có băm khác băm đã ghi lúc sinh. */
  public static final class HashMismatchException extends IOException {
    private static final long serialVersionUID = 1L;

    public HashMismatchException(String entry) {
      super("Tệp " + entry + " có mã băm không khớp manifest");
    }
  }

  /** Tên tệp biểu mẫu trong ZIP. */
  static String entryName(FormRow f) {
    return FORMS_DIR + f.formCode() + "/" + f.id() + ".pdf";
  }

  /**
   * Ghi ZIP vào {@code out} (không đóng {@code out}).
   *
   * @param signatureKey khoá tệp chữ ký manifest, {@code null} khi gói chưa ký
   */
  public static Result write(
      PackageFiles files,
      TenantId tenant,
      OutputStream out,
      String manifestJson,
      String infoJson,
      String signatureKey,
      List<FormRow> forms)
      throws IOException {
    Counting counting = new Counting(out);
    ZipOutputStream zip = new ZipOutputStream(counting, StandardCharsets.UTF_8);
    // PDF/A đã nén bên trong: không nén lại, ZIP chỉ để đóng gói (nhanh, kích thước gần bằng tổng
    // tệp).
    zip.setLevel(Deflater.NO_COMPRESSION);
    byte[] buffer = new byte[BUFFER];
    putBytes(zip, MANIFEST, manifestJson.getBytes(StandardCharsets.UTF_8));
    if (signatureKey != null) {
      try (InputStream in = files.open(tenant, signatureKey)) {
        zip.putNextEntry(new ZipEntry(SIGNATURE));
        copy(in, zip, buffer, null);
        zip.closeEntry();
      }
    }
    putBytes(zip, INFO, infoJson.getBytes(StandardCharsets.UTF_8));
    int count = 0;
    for (FormRow f : forms) {
      String name = entryName(f);
      zip.putNextEntry(new ZipEntry(name));
      byte[] digest;
      try (InputStream in = files.open(tenant, f.objectKey())) {
        digest = copy(in, zip, buffer, sha256());
      }
      zip.closeEntry();
      if (f.pdfSha256() != null && !Arrays.equals(digest, f.pdfSha256())) {
        throw new HashMismatchException(name);
      }
      count++;
    }
    // Đóng ZIP để giải phóng Deflater; Counting chỉ flush, không đóng luồng đích.
    zip.close();
    return new Result(counting.count, count);
  }

  private static void putBytes(ZipOutputStream zip, String name, byte[] content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  private static byte[] copy(InputStream in, OutputStream out, byte[] buffer, MessageDigest md)
      throws IOException {
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      out.write(buffer, 0, n);
      if (md != null) {
        md.update(buffer, 0, n);
      }
    }
    return md == null ? null : md.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Đếm byte đi qua, không đóng luồng đích. */
  private static final class Counting extends FilterOutputStream {
    long count;

    Counting(OutputStream out) {
      super(out);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      count += len;
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }
}
