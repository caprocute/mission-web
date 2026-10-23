package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.IngestService.Ingest;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.StoredObject;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Nhận tệp gốc văn bản đã ban hành từ integration-gateway (CORE-03-04): ghi kho khoá, siêu dữ liệu,
 * sự kiện lưu; gọi lại cùng khoá trả cùng đối tượng; cùng băm dùng lại bản vật lý; từ chối kiểu
 * sai, quá cỡ, bản mã.
 */
class IngestServiceTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-0000000f0101");
  static final UUID USER = UUID.fromString("0192f1a2-0000-7000-8000-0000000f0102");
  static final UUID EVIDENCE = UUID.fromString("0192f1a2-0000-7000-8000-0000000f0103");
  static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");

  final Map<UUID, StoredFile> rows = new ConcurrentHashMap<>();
  final List<StoredFile> events = new ArrayList<>();
  final List<String> names = new ArrayList<>();
  final ObjectStorage storage = mock(ObjectStorage.class);
  boolean raceOnInsert;

  final StoredFiles files =
      new StoredFiles() {
        @Override
        public void insert(StoredFile file, byte[] fileNameEnc, UUID dekId) {
          if (raceOnInsert) {
            rows.put(file.objectId(), file);
            throw new DuplicateKeyException("trùng");
          }
          rows.put(file.objectId(), file);
        }

        @Override
        public Optional<StoredFile> find(UUID objectId, Integer versionNo) {
          return Optional.ofNullable(rows.get(objectId));
        }

        @Override
        public Optional<StoredObject> physicalCopy(byte[] sha256) {
          return rows.values().stream()
              .filter(f -> Arrays.equals(f.sha256(), sha256))
              .findFirst()
              .map(f -> new StoredObject(f.location(), f.cephVersionId(), f.lockUntil()));
        }

        @Override
        public int registerReference(byte[] sha256, String objectKey, UUID owner) {
          return 1;
        }

        @Override
        public boolean hasOtherCopy(byte[] sha256, UUID versionId) {
          return false;
        }
      };

  final IngestService service =
      new IngestService(
          files,
          events::add,
          (tenant, id, name) -> {
            names.add(name);
            return new FileNameCipher.Sealed(new byte[] {1}, UUID.randomUUID());
          },
          storage,
          new TenantBucketService(storage, "dev", 1),
          TransactionOperations.withoutTransaction(),
          new UploadService.Settings("dev", 10_000, 5 * 1024 * 1024, Duration.ofHours(24), 1),
          Clock.fixed(NOW, ZoneOffset.UTC));

  static Ingest cmd(String key, String type, byte[] content) {
    return new Ingest(TENANT, key, EVIDENCE, USER, "47-BC.pdf", type, content);
  }

  void stubPut() {
    when(storage.put(any(), any(), any(), any()))
        .thenAnswer(
            inv -> new StoredObject(inv.getArgument(0), "v-1", inv.getArgument(3, Instant.class)));
  }

  @Test
  void ghiKhoKhoaSieuDuLieuVaSuKienRoiGoiLaiTraCungDoiTuong() throws Exception {
    stubPut();
    byte[] content = UploadServiceTest.pdf(1000);
    StoredFile f = service.ingest(cmd("k-1", "application/pdf", content));

    assertThat(f.objectId()).isEqualTo(IngestService.objectIdOf(TENANT, "k-1"));
    assertThat(f.versionNo()).isEqualTo(1);
    assertThat(f.ownerModule()).isEqualTo("product");
    assertThat(f.ownerRefId()).isEqualTo(EVIDENCE);
    assertThat(f.createdBy()).isEqualTo(USER);
    assertThat(f.classification()).isEqualTo("C2");
    assertThat(f.scanStatus()).isEqualTo("CHO_QUET");
    assertThat(f.sha256()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(content));
    assertThat(f.lockUntil()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    assertThat(events).containsExactly(f);
    assertThat(names).containsExactly("47-BC.pdf");
    verify(storage)
        .put(
            eq(ObjectLocation.of("dev", TENANT, f.sha256())),
            eq(content),
            eq("application/pdf"),
            any());

    assertThat(service.ingest(cmd("k-1", "application/pdf", content))).isEqualTo(f);
    assertThat(events).hasSize(1);

    // Khoá khác, cùng nội dung: dùng lại bản vật lý, không ghi kho lần nữa.
    StoredFile again = service.ingest(cmd("k-2", "application/pdf", content));
    assertThat(again.objectKey()).isEqualTo(f.objectKey());
    verify(storage, times(1)).put(any(), any(), any(), any());
    verify(storage, never()).extendLock(any(), any(), any());
  }

  @Test
  void dungLaiBanVatLyKeoDaiKhoaKhiThieu() {
    stubPut();
    byte[] content = UploadServiceTest.pdf(800);
    StoredFile a = service.ingest(cmd("k-a", "application/pdf", content));
    rows.put(a.objectId(), UploadServiceTest.withLock(a, NOW.minus(Duration.ofDays(1))));
    StoredFile b = service.ingest(cmd("k-b", "application/pdf", content));
    assertThat(b.lockUntil()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    verify(storage).extendLock(a.location(), a.cephVersionId(), NOW.plus(Duration.ofDays(1)));
  }

  @Test
  void tuChoiKieuSaiQuaCoBanMaVaThamSoThieu() {
    byte[] pdf = UploadServiceTest.pdf(500);
    assertThatThrownBy(() -> service.ingest(cmd("k", "text/html", pdf)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("FILE-415");
    assertThatThrownBy(() -> service.ingest(cmd("k", "application/octet-stream", pdf)))
        .hasMessageContaining("FILE-415");
    assertThatThrownBy(() -> service.ingest(cmd("k", "application/pdf", new byte[20_000])))
        .hasMessageContaining("FILE-413");
    assertThatThrownBy(() -> service.ingest(cmd("k", "application/pdf", new byte[0])))
        .hasMessageContaining("FILE-413");
    assertThatThrownBy(() -> service.ingest(cmd("k", "image/png", pdf)))
        .hasMessageContaining("FILE-422-CONTENT-TYPE-MISMATCH");
    assertThatThrownBy(() -> cmd(" ", "application/pdf", pdf))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(events).isEmpty();
  }

  @Test
  void lenhSongSongCungKhoaTraBanDaGhi() {
    stubPut();
    raceOnInsert = true;
    StoredFile f = service.ingest(cmd("k-r", "application/pdf", UploadServiceTest.pdf(600)));
    assertThat(f.objectId()).isEqualTo(IngestService.objectIdOf(TENANT, "k-r"));
  }
}
