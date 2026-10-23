package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ghép ZIP theo luồng (CORE-06-08): bố cục, đối chiếu băm và bộ nhớ khi gói 1 GB. */
class PackageArchiveTest {

  private static final Logger LOG = LoggerFactory.getLogger(PackageArchiveTest.class);
  static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a06-0000-7000-8000-00000000cc01"));

  final TransferFakes.Files files = new TransferFakes.Files();

  static FormRow form(String code, String key, byte[] sha) {
    return new FormRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        20260,
        UUID.randomUUID(),
        UUID.randomUUID(),
        code,
        "1",
        new byte[32],
        UUID.randomUUID(),
        key,
        sha,
        FormStatus.DA_SINH,
        null);
  }

  static byte[] sha(byte[] content) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(content);
  }

  @Test
  void boCucZipVaManifestDaKy() throws Exception {
    byte[] pdf1 = "%PDF-1 mau 3".getBytes(StandardCharsets.UTF_8);
    byte[] pdf2 = "%PDF-1 bang cham diem".getBytes(StandardCharsets.UTF_8);
    files.put("k/1", pdf1);
    files.put("k/2", pdf2);
    files.put("ky/goi.p7s", new byte[] {1, 2, 3});
    FormRow a = form("MAU_3", "k/1", sha(pdf1));
    FormRow b = form("BANG_CHAM_DIEM", "k/2", sha(pdf2));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    PackageArchive.Result r =
        PackageArchive.write(
            files, TENANT, out, "[{\"a\":1}]", "{\"maGoi\":\"DP\"}", "ky/goi.p7s", List.of(a, b));

    assertThat(r.files()).isEqualTo(2);
    assertThat(r.bytes()).isEqualTo(out.size());
    Map<String, byte[]> entries = unzip(out.toByteArray());
    assertThat(entries.keySet())
        .containsExactly(
            "manifest.json",
            "manifest.json.p7s",
            "thong-tin-goi.json",
            "bieu-mau/MAU_3/" + a.id() + ".pdf",
            "bieu-mau/BANG_CHAM_DIEM/" + b.id() + ".pdf");
    assertThat(new String(entries.get("manifest.json"), StandardCharsets.UTF_8))
        .isEqualTo("[{\"a\":1}]");
    assertThat(entries.get("bieu-mau/MAU_3/" + a.id() + ".pdf")).isEqualTo(pdf1);
  }

  @Test
  void khongChuKyThiKhongCoTepP7s() throws Exception {
    byte[] pdf = "%PDF".getBytes(StandardCharsets.UTF_8);
    files.put("k/1", pdf);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PackageArchive.write(files, TENANT, out, "[]", "{}", null, List.of(form("MAU_3", "k/1", null)));
    assertThat(unzip(out.toByteArray())).doesNotContainKey("manifest.json.p7s").hasSize(3);
  }

  @Test
  void bamLechThiDung() {
    files.put("k/1", "%PDF that".getBytes(StandardCharsets.UTF_8));
    FormRow f = form("MAU_3", "k/1", new byte[32]);
    assertThatThrownBy(
            () ->
                PackageArchive.write(
                    files, TENANT, OutputStream.nullOutputStream(), "[]", "{}", null, List.of(f)))
        .isInstanceOf(PackageArchive.HashMismatchException.class)
        .hasMessageContaining(f.id().toString());
  }

  /**
   * Tiêu chí hoàn thành CORE-06-08: ZIP 1 GB dựng được mà heap không tăng tương ứng. 16 tệp × 64
   * MiB nội dung giả ngẫu nhiên (khó nén như PDF/A) sinh theo luồng, ZIP ghi vào luồng bỏ đi; đo
   * heap đang dùng mỗi 20 ms trong lúc ghép.
   */
  @Test
  void zip1GbHeapKhongTangTheoKichThuoc() throws Exception {
    int fileCount = 16;
    long perFile = 64L * 1024 * 1024;
    byte[] block = new byte[1024 * 1024 + 7];
    new Random(42).nextBytes(block);
    List<FormRow> forms = new ArrayList<>();
    for (int i = 0; i < fileCount; i++) {
      String key = "lon/" + i;
      files.stored.put(key, () -> new Generated(block, perFile));
      forms.add(form("BANG_CHAM_DIEM", key, null));
    }
    Runtime rt = Runtime.getRuntime();
    System.gc();
    long baseline = rt.totalMemory() - rt.freeMemory();
    AtomicLong peak = new AtomicLong(baseline);
    AtomicBoolean running = new AtomicBoolean(true);
    Thread sampler =
        new Thread(
            () -> {
              while (running.get()) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                try {
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  return;
                }
              }
            });
    sampler.start();
    long started = System.nanoTime();
    PackageArchive.Result r;
    try {
      r =
          PackageArchive.write(
              files, TENANT, OutputStream.nullOutputStream(), "[]", "{}", null, forms);
    } finally {
      running.set(false);
      sampler.join();
    }
    long millis = (System.nanoTime() - started) / 1_000_000;
    long growth = peak.get() - baseline;
    String measured =
        String.format(
            "ZIP %d byte từ %d byte tệp nguồn trong %d ms; heap nền %d MiB, đỉnh %d MiB, tăng %d MiB,"
                + " heap tối đa %d MiB",
            r.bytes(),
            fileCount * perFile,
            millis,
            baseline >> 20,
            peak.get() >> 20,
            growth >> 20,
            rt.maxMemory() >> 20);
    LOG.info(measured);
    java.nio.file.Path report = java.nio.file.Path.of("target", "do-luong", "zip-1gb.txt");
    java.nio.file.Files.createDirectories(report.getParent());
    java.nio.file.Files.writeString(report, measured + System.lineSeparator());
    assertThat(r.files()).isEqualTo(fileCount);
    assertThat(r.bytes()).isGreaterThan(fileCount * perFile);
    // Bộ đệm chép 64 KB, Deflater vài trăm KB: heap tăng phải nhỏ hơn rất nhiều so với 1 GiB.
    assertThat(growth).isLessThan(96L * 1024 * 1024);
  }

  /** Luồng sinh {@code size} byte bằng cách lặp một khối, không cấp phát khi đọc. */
  static final class Generated extends InputStream {
    private final byte[] block;
    private final long size;
    private long pos;

    Generated(byte[] block, long size) {
      this.block = block;
      this.size = size;
    }

    @Override
    public int read() {
      if (pos >= size) {
        return -1;
      }
      return block[(int) (pos++ % block.length)] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (pos >= size) {
        return -1;
      }
      int n = (int) Math.min(len, size - pos);
      int start = (int) (pos % block.length);
      n = Math.min(n, block.length - start);
      System.arraycopy(block, start, b, off, n);
      pos += n;
      return n;
    }
  }

  static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    Map<String, byte[]> out = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        out.put(e.getName(), in.readAllBytes());
      }
    }
    return out;
  }
}
