package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** Đọc chữ minh chứng (DV-08-05): hàng đợi, thử lại, chặn C3 và cổng cấp nội dung. */
class OcrServicesTest {

  static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000c01");

  /** Hàng đợi trong bộ nhớ: trạng thái, số lần nhận, thời điểm đến hạn. */
  final Map<UUID, StoredFile> files = new LinkedHashMap<>();

  final Map<UUID, String> status = new HashMap<>();
  final Map<UUID, Integer> attempts = new HashMap<>();
  final Map<UUID, Instant> nextAt = new HashMap<>();
  final Map<UUID, OcrQueue.Text> texts = new HashMap<>();
  final List<String> keys = new ArrayList<>();

  final OcrQueue queue =
      new OcrQueue() {
        @Override
        public List<Due> due(int limit) {
          return status.entrySet().stream()
              .filter(e -> "CHO_DOC".equals(e.getValue()))
              .filter(e -> !nextAt.getOrDefault(e.getKey(), NOW).isAfter(NOW))
              .limit(limit)
              .map(e -> new Due(e.getKey(), TENANT))
              .toList();
        }

        @Override
        public Optional<Claim> claim(UUID versionId, Duration lease) {
          if (!"CHO_DOC".equals(status.get(versionId))) {
            return Optional.empty();
          }
          int n = attempts.merge(versionId, 1, Integer::sum);
          nextAt.put(versionId, NOW.plus(lease));
          return Optional.of(new Claim(files.get(versionId), n));
        }

        @Override
        public void retryAt(UUID versionId, Instant at) {
          nextAt.put(versionId, at);
        }

        @Override
        public boolean done(UUID versionId, Text text) {
          texts.put(versionId, text);
          return set(versionId, "DA_DOC");
        }

        @Override
        public boolean unreadable(UUID versionId) {
          return set(versionId, "KHONG_DOC_DUOC");
        }

        @Override
        public boolean failed(UUID versionId) {
          return set(versionId, "LOI");
        }

        @Override
        public Optional<String> status(UUID versionId) {
          return Optional.ofNullable(status.get(versionId));
        }

        private boolean set(UUID versionId, String s) {
          return status.replace(versionId, "CHO_DOC", s);
        }
      };

  /** Kịch bản trả lời của ai-gateway theo lượt gọi. */
  final List<Object> replies = new ArrayList<>();

  final OcrGateway gateway =
      (request, key) -> {
        keys.add(key + " " + request.objectKey() + " " + request.classification());
        Object r = replies.isEmpty() ? new IOException("hết kịch bản") : replies.remove(0);
        if (r instanceof IOException e) {
          throw e;
        }
        if (r instanceof RuntimeException e) {
          throw e;
        }
        return (OcrGateway.Result) r;
      };

  final OcrTextCipher cipher =
      (tenant, version, text) ->
          new OcrTextCipher.Sealed(
              ("enc:" + text).getBytes(StandardCharsets.UTF_8), new UUID(0, 7));

  OcrService service(Duration retryBase) {
    return new OcrService(
        queue,
        gateway,
        cipher,
        TransactionOperations.withoutTransaction(),
        new OcrService.Settings(Duration.ofMinutes(10), retryBase, 3),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  StoredFile add(String mediaType, String cls, String scan) {
    UUID object = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    StoredFile f =
        new StoredFile(
            object,
            version,
            1,
            TENANT,
            UUID.randomUUID(),
            "product",
            null,
            mediaType,
            cls,
            10,
            new byte[] {(byte) 0xab, 0x01},
            "b",
            "k/" + version,
            "v1",
            null,
            scan,
            NOW);
    files.put(version, f);
    status.put(version, "CHO_DOC");
    return f;
  }

  @Test
  void canDocChuChiAnhVaPdfKhongPhaiC3() {
    assertThat(OcrService.needsOcr(add("application/pdf", "C1", "SACH"))).isTrue();
    assertThat(OcrService.needsOcr(add("image/jpeg", "C2", "SACH"))).isTrue();
    assertThat(OcrService.needsOcr(add("image/png", "C3", "SACH"))).isFalse();
    assertThat(OcrService.needsOcr(add("application/zip", "C1", "SACH"))).isFalse();
    StoredFile f = add("image/png", "C1", "SACH");
    assertThat(OcrService.objectKey(f)).isEqualTo(TENANT + "/" + f.objectId() + "/1");
  }

  @Test
  void docXongThiMaHoaRoiGhiVaKhongGoiLai() {
    StoredFile f = add("application/pdf", "C2", "SACH");
    replies.add(new OcrGateway.Result("vt-ocr", "Quyết định giao chỉ tiêu", 3, null));
    OcrService s = service(Duration.ZERO);

    assertThat(s.readDue(10)).isEqualTo(1);
    assertThat(status.get(f.versionId())).isEqualTo("DA_DOC");
    OcrQueue.Text t = texts.get(f.versionId());
    assertThat(new String(t.textEnc(), StandardCharsets.UTF_8))
        .isEqualTo("enc:Quyết định giao chỉ tiêu");
    assertThat(t.dekId()).isEqualTo(new UUID(0, 7));
    assertThat(t.modelId()).isEqualTo("vt-ocr");
    assertThat(t.pageCount()).isEqualTo(3);
    // Ai-gateway không trả thời điểm xử lý thì lấy giờ của worker.
    assertThat(t.processedAt()).isEqualTo(NOW);
    assertThat(keys)
        .containsExactly("ocr-" + f.versionId() + "-1 " + TENANT + "/" + f.objectId() + "/1 C2");

    assertThat(s.readDue(10)).isZero();
    assertThat(keys).hasSize(1);
  }

  @Test
  void loiTamThoiThuLaiTheoLuyThuaRoiLoi() {
    StoredFile f = add("image/png", "C1", "SACH");
    replies.add(new IOException("503"));
    replies.add(new IllegalStateException("kết nối đứt"));
    replies.add(new IOException("429"));
    OcrService s = service(Duration.ofSeconds(30));

    s.readDue(10);
    assertThat(status.get(f.versionId())).isEqualTo("CHO_DOC");
    assertThat(nextAt.get(f.versionId())).isEqualTo(NOW.plusSeconds(30));
    // Chưa đến hạn thì không lấy lại.
    assertThat(s.readDue(10)).isZero();

    nextAt.put(f.versionId(), NOW);
    s.readDue(10);
    assertThat(nextAt.get(f.versionId())).isEqualTo(NOW.plusSeconds(60));

    nextAt.put(f.versionId(), NOW);
    s.readDue(10);
    assertThat(status.get(f.versionId())).isEqualTo("LOI");
    assertThat(keys).hasSize(3);
    assertThat(keys.get(2)).startsWith("ocr-" + f.versionId() + "-3 ");
  }

  @Test
  void khongDuNetVaTuChoiLaKetLuanCuoi() {
    StoredFile blurry = add("image/jpeg", "C1", "SACH");
    replies.add(new OcrGateway.UnreadableException("422"));
    service(Duration.ZERO).readDue(1);
    assertThat(status.get(blurry.versionId())).isEqualTo("KHONG_DOC_DUOC");

    StoredFile refused = add("application/pdf", "C1", "SACH");
    replies.add(new OcrGateway.RefusedException("403"));
    service(Duration.ZERO).readDue(10);
    assertThat(status.get(refused.versionId())).isEqualTo("LOI");
    assertThat(keys).hasSize(2);
  }

  @Test
  void tepC3LotVaoHangDoiKhongDuocGuiDi() {
    StoredFile c3 = add("application/pdf", "C3", "SACH");
    StoredFile other = add("application/zip", "C1", "SACH");
    service(Duration.ZERO).readDue(10);
    assertThat(status.get(c3.versionId())).isEqualTo("LOI");
    assertThat(status.get(other.versionId())).isEqualTo("LOI");
    assertThat(keys).isEmpty();
  }

  @Test
  void viecDaDuocWorkerKhacNhanThiBoQua() {
    StoredFile f = add("application/pdf", "C1", "SACH");
    status.put(f.versionId(), "DA_DOC");
    OcrService s = service(Duration.ZERO);
    s.readOne(f.versionId());
    assertThat(keys).isEmpty();
  }

  @Test
  void loiGhiKetQuaKhongDungCaLo() {
    StoredFile a = add("application/pdf", "C1", "SACH");
    StoredFile b = add("application/pdf", "C1", "SACH");
    replies.add(new OcrGateway.Result("m", "a", 1, NOW));
    replies.add(new OcrGateway.Result("m", "b", 1, NOW));
    OcrTextCipher broken =
        (tenant, version, text) -> {
          if (version.equals(a.versionId())) {
            throw new IllegalStateException("không lấy được khoá");
          }
          return cipher.seal(tenant, version, text);
        };
    OcrService s =
        new OcrService(
            queue,
            gateway,
            broken,
            TransactionOperations.withoutTransaction(),
            new OcrService.Settings(Duration.ofMinutes(10), Duration.ZERO, 3),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(s.readDue(10)).isEqualTo(2);
    assertThat(status.get(a.versionId())).isEqualTo("CHO_DOC");
    assertThat(status.get(b.versionId())).isEqualTo("DA_DOC");
  }

  // ---------------------------------------------------------------- cổng cấp nội dung

  OcrContentService content(StoredFile f, byte[] bytes) {
    StoredFiles stored =
        (StoredFiles)
            Proxy.newProxyInstance(
                StoredFiles.class.getClassLoader(),
                new Class<?>[] {StoredFiles.class},
                (p, m, args) -> {
                  if (m.getName().equals("find")) {
                    return f != null && f.objectId().equals(args[0]) && args[1].equals(1)
                        ? Optional.of(f)
                        : Optional.empty();
                  }
                  throw new UnsupportedOperationException(m.getName());
                });
    ObjectStorage storage =
        (ObjectStorage)
            Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(),
                new Class<?>[] {ObjectStorage.class},
                (p, m, args) -> {
                  if (m.getName().equals("stream")) {
                    return ((ObjectStorage.StreamReader<?>) args[2])
                        .read(new ByteArrayInputStream(bytes));
                  }
                  throw new UnsupportedOperationException(m.getName());
                });
    return new OcrContentService(stored, storage, TransactionOperations.withoutTransaction());
  }

  @Test
  void congNoiBoChiCapTepSachDocChuDuoc() throws IOException {
    StoredFile ok = add("application/pdf", "C1", "SACH");
    byte[] bytes = "%PDF-1.7 nội dung".getBytes(StandardCharsets.UTF_8);
    OcrContentService svc = content(ok, bytes);
    StoredFile found = svc.authorize(TENANT, ok.objectId(), 1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    svc.copy(found, out);
    assertThat(out.toByteArray()).isEqualTo(bytes);

    assertThatThrownBy(() -> svc.authorize(TENANT, UUID.randomUUID(), 1))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> svc.authorize(UUID.randomUUID(), ok.objectId(), 1))
        .isInstanceOf(NoSuchElementException.class);

    StoredFile c3 = add("application/pdf", "C3", "SACH");
    assertThatThrownBy(() -> content(c3, bytes).authorize(TENANT, c3.objectId(), 1))
        .isInstanceOf(OcrContentService.DeniedException.class);
    StoredFile infected = add("application/pdf", "C1", "NHIEM");
    assertThatThrownBy(() -> content(infected, bytes).authorize(TENANT, infected.objectId(), 1))
        .isInstanceOf(OcrContentService.DeniedException.class);
    StoredFile zip = add("application/zip", "C1", "SACH");
    assertThatThrownBy(() -> content(zip, bytes).authorize(TENANT, zip.objectId(), 1))
        .isInstanceOf(OcrContentService.DeniedException.class);
  }
}
