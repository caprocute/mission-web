package vn.viettel.okrkpi.evidence.domain;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Đọc tệp đúng một lượt theo luồng: tính SHA-256 và kích thước (BR-07-02), nhận kiểu thật theo chữ
 * ký đầu tệp và cấu trúc gói Office, phát hiện macro (SEC-48). Không đệm cả tệp vào bộ nhớ.
 *
 * <p>SEC-48 ghi dùng Apache Tika; ở đây kiểm chữ ký tệp và danh mục mục nén của năm kiểu được phép
 * để không thêm phụ thuộc mới (wiki trang 14 mục 47).
 */
public final class ContentInspector {

  /** Giới hạn dữ liệu giải nén của gói Office, chống bom nén. */
  static final long MAX_INFLATED = 1L << 30;

  private static final int MAX_ENTRIES = 20_000;
  private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
  private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
  private static final byte[] ZIP = {'P', 'K', 3, 4};

  private ContentInspector() {}

  /** Kết quả đọc: băm, kích thước, kiểu thật (rỗng khi không nhận ra) và có macro không. */
  public record Inspection(byte[] sha256, long size, FileKind detected, boolean hasMacro) {

    /** Nội dung khớp kiểu khai báo và không có macro. */
    public boolean matches(FileKind declared) {
      return declared == detected && !hasMacro;
    }
  }

  /**
   * Đọc hết {@code in}. Với {@code sniff = false} (bản mã C3) chỉ tính băm và kích thước.
   *
   * @throws IOException khi luồng lỗi
   */
  public static Inspection inspect(InputStream in, boolean sniff) throws IOException {
    MessageDigest digest = sha256();
    CountingStream counted = new CountingStream(in);
    BufferedInputStream buffered =
        new BufferedInputStream(new DigestInputStream(counted, digest), 64 * 1024);
    FileKind detected = null;
    boolean macro = false;
    if (sniff) {
      buffered.mark(16);
      byte[] head = buffered.readNBytes(8);
      buffered.reset();
      if (startsWith(head, PDF)) {
        detected = FileKind.PDF;
      } else if (startsWith(head, PNG)) {
        detected = FileKind.PNG;
      } else if (startsWith(head, JPEG)) {
        detected = FileKind.JPEG;
      } else if (startsWith(head, ZIP)) {
        OfficePackage pkg = readOfficePackage(buffered);
        detected = pkg.kind();
        macro = pkg.macro();
      }
    }
    buffered.transferTo(OutputStream.nullOutputStream());
    return new Inspection(digest.digest(), counted.count, detected, macro);
  }

  private record OfficePackage(FileKind kind, boolean macro) {}

  private static OfficePackage readOfficePackage(InputStream in) throws IOException {
    boolean word = false;
    boolean excel = false;
    boolean macro = false;
    long inflated = 0;
    byte[] buf = new byte[8192];
    try {
      ZipInputStream zip = new ZipInputStream(in);
      ZipEntry entry;
      int entries = 0;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          return new OfficePackage(null, false);
        }
        String name = entry.getName().toLowerCase(Locale.ROOT);
        word |= "word/document.xml".equals(name);
        excel |= "xl/workbook.xml".equals(name);
        macro |= name.endsWith("vbaproject.bin") || name.endsWith(".bin") && name.contains("vba");
        int r;
        while ((r = zip.read(buf)) > 0) {
          inflated += r;
          if (inflated > MAX_INFLATED) {
            return new OfficePackage(null, false);
          }
        }
      }
    } catch (java.util.zip.ZipException | java.io.EOFException e) {
      // Gói hỏng hoặc cụt: không nhận là tệp Office.

      return new OfficePackage(null, false);
    }
    FileKind kind = word && !excel ? FileKind.DOCX : excel && !word ? FileKind.XLSX : null;
    return new OfficePackage(kind, macro);
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    if (data.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (data[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }

  private static final class CountingStream extends java.io.FilterInputStream {
    private long count;

    CountingStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int r = super.read(b, off, len);
      if (r > 0) {
        count += r;
      }
      return r;
    }
  }
}
