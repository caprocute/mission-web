package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.Part;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.StoredObject;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.UploadGoneException;
import vn.viettel.okrkpi.evidence.application.UploadService.CreateUpload;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.evidence.domain.UploadSession;
import vn.viettel.okrkpi.evidence.domain.UploadSession.Status;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class UploadServiceTest {

  static final int PART = 5 * 1024 * 1024;
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-0000000f0001");
  static final UUID USER = UUID.fromString("0192f1a2-0000-7000-8000-0000000f0002");
  static final Instant NOW = Instant.parse("2027-03-01T02:00:00Z");

  final Map<UUID, UploadSession> sessionRows = new ConcurrentHashMap<>();
  final Map<UUID, StoredFile> fileRows = new ConcurrentHashMap<>();
  final List<StoredFile> events = new ArrayList<>();
  final ObjectStorage storage = mock(ObjectStorage.class);
  final FileAccessGuard guard = mock(FileAccessGuard.class);

  final UploadSessions sessions =
      new UploadSessions() {
        @Override
        public void insert(UploadSession s) {
          sessionRows.put(s.id(), s);
        }

        @Override
        public Optional<UploadSession> find(UUID id) {
          return Optional.ofNullable(sessionRows.get(id));
        }

        @Override
        public void updatePartsDone(UUID id, int partsDone, UUID actor) {
          sessionRows.computeIfPresent(id, (k, s) -> with(s, s.status(), partsDone));
        }

        @Override
        public boolean close(UUID id, Status to, UUID actor) {
          UploadSession s = sessionRows.get(id);
          if (s == null || s.status() != Status.MO) {
            return false;
          }
          sessionRows.put(id, with(s, to, s.partsDone()));
          return true;
        }

        @Override
        public List<Expired> expired(int limit) {
          return sessionRows.values().stream()
              .filter(s -> s.status() == Status.MO && s.expiresAt().isBefore(NOW))
              .map(s -> new Expired(s.id(), s.tenantId()))
              .toList();
        }
      };

  final StoredFiles files =
      new StoredFiles() {
        @Override
        public void insert(StoredFile file, byte[] fileNameEnc, UUID dekId) {
          fileRows.put(file.objectId(), file);
        }

        @Override
        public Optional<StoredFile> find(UUID objectId, Integer versionNo) {
          return Optional.ofNullable(fileRows.get(objectId));
        }

        @Override
        public Optional<ObjectStorage.StoredObject> physicalCopy(byte[] sha256) {
          return fileRows.values().stream()
              .filter(f -> java.util.Arrays.equals(f.sha256(), sha256))
              .findFirst()
              .map(
                  f ->
                      new ObjectStorage.StoredObject(
                          f.location(), f.cephVersionId(), f.lockUntil()));
        }

        @Override
        public int registerReference(byte[] sha256, String objectKey, UUID owner) {
          return refCounts.merge(HexFormat.of().formatHex(sha256), 1, Integer::sum);
        }

        @Override
        public boolean hasOtherCopy(byte[] sha256, UUID versionId) {
          return fileRows.values().stream()
              .anyMatch(
                  f ->
                      java.util.Arrays.equals(f.sha256(), sha256)
                          && !f.versionId().equals(versionId));
        }
      };

  final Map<String, Integer> refCounts = new java.util.HashMap<>();

  final UploadService service =
      new UploadService(
          sessions,
          files,
          events::add,
          (tenant, id, name) -> new FileNameCipher.Sealed(new byte[] {1}, UUID.randomUUID()),
          storage,
          new TenantBucketService(storage, "dev", 1),
          guard,
          TransactionOperations.withoutTransaction(),
          new UploadService.Settings("dev", 50L * 1024 * 1024, PART, Duration.ofHours(24), 1),
          Clock.fixed(NOW, ZoneOffset.UTC));

  static StoredFile withLock(StoredFile f, Instant lockUntil) {
    return new StoredFile(
        f.objectId(),
        f.versionId(),
        f.versionNo(),
        f.tenantId(),
        f.createdBy(),
        f.ownerModule(),
        f.ownerRefId(),
        f.mediaType(),
        f.classification(),
        f.sizeBytes(),
        f.sha256(),
        f.bucket(),
        f.objectKey(),
        f.cephVersionId(),
        lockUntil,
        f.scanStatus(),
        f.createdAt());
  }

  static UploadSession with(UploadSession s, Status status, int partsDone) {
    return new UploadSession(
        s.id(),
        s.tenantId(),
        s.userId(),
        s.rgwUploadId(),
        s.declaredSha256(),
        s.declaredSize(),
        s.partSize(),
        partsDone,
        s.expiresAt(),
        status,
        s.kind(),
        s.classification(),
        s.ownerModule(),
        s.ownerRefId(),
        s.fileNameEnc(),
        s.dekId());
  }

  <T> T inTenant(java.util.concurrent.Callable<T> work) throws Exception {
    return TenantContext.call(TenantId.of(TENANT), work);
  }

  UploadSession open(byte[] content, String type, String cls, boolean e2ee) throws Exception {
    when(storage.createMultipart(any(), anyString())).thenReturn("rgw-1");
    return inTenant(
        () ->
            service.create(
                new CreateUpload(
                    "a.pdf", type, content.length, sha(content), "product", null, cls, e2ee),
                USER));
  }

  @Test
  void moPhienKiemKieuKichThuocVaMat() throws Exception {
    UploadSession s = open(pdf(100), "application/pdf", "C1", false);
    assertThat(s.status()).isEqualTo(Status.MO);
    assertThat(s.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    assertThat(sessionRows).containsKey(s.id());
    verify(storage).ensureStagingBucket("ev-dev-tam", 2);

    assertThat(code(() -> open(pdf(1), "application/zip", "C1", false)))
        .isEqualTo("FILE-415-UNSUPPORTED-TYPE");
    assertThat(code(() -> open(pdf(1), "application/octet-stream", "C1", false)))
        .isEqualTo("FILE-415-UNSUPPORTED-TYPE");
    assertThat(code(() -> open(pdf(1), "application/pdf", "C3", false)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    inTenant(
                        () ->
                            service.create(
                                new CreateUpload(
                                    "a",
                                    "application/pdf",
                                    51L * 1024 * 1024,
                                    sha(pdf(1)),
                                    "product",
                                    null,
                                    "C1",
                                    false),
                                USER))))
        .isEqualTo("FILE-413-TOO-LARGE");
    assertThat(
            code(
                () ->
                    inTenant(
                        () ->
                            service.create(
                                new CreateUpload(
                                    "a", "application/pdf", 1, "xyz", "product", null, "C1", false),
                                USER))))
        .isEqualTo("COMMON-422-VALIDATION");
  }

  @Test
  void guiPhanKiemChuPhienVaKichThuoc() throws Exception {
    byte[] content = pdf(PART + 10);
    UploadSession s = open(content, "application/pdf", "C1", false);
    when(storage.uploadPart(any(), eq("rgw-1"), anyInt(), any(), anyLong())).thenReturn("\"e\"");
    when(storage.listParts(any(), eq("rgw-1"))).thenReturn(List.of(new Part(1, PART, "\"e\"")));

    Part p = service.uploadPart(s.id(), 1, PART, new ByteArrayInputStream(new byte[0]), USER);
    assertThat(p.etag()).isEqualTo("\"e\"");
    assertThat(sessionRows.get(s.id()).partsDone()).isEqualTo(1);
    assertThat(service.view(s.id(), USER).receivedParts()).hasSize(1);

    assertThat(code(() -> service.uploadPart(s.id(), 1, PART, null, UUID.randomUUID())))
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
    assertThat(code(() -> service.uploadPart(s.id(), 3, 10, null, USER)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(code(() -> service.uploadPart(s.id(), 1, PART + 1, null, USER)))
        .isEqualTo("FILE-413-PART-TOO-LARGE");
    assertThat(code(() -> service.uploadPart(s.id(), 2, 9, null, USER)))
        .isEqualTo("FILE-422-SIZE-MISMATCH");
    when(storage.uploadPart(any(), eq("rgw-1"), eq(2), any(), anyLong()))
        .thenThrow(new UploadGoneException("x", null));
    assertThat(code(() -> service.uploadPart(s.id(), 2, 10, null, USER)))
        .isEqualTo("FILE-409-UPLOAD-CLOSED");
    org.mockito.Mockito.reset(storage);
    when(storage.uploadPart(any(), any(), anyInt(), any(), anyLong()))
        .thenThrow(new ObjectStorage.PartInterruptedException("x", null));
    assertThat(code(() -> service.uploadPart(s.id(), 2, 10, null, USER)))
        .isEqualTo("FILE-422-SIZE-MISMATCH");
    assertThatThrownBy(() -> service.view(UUID.randomUUID(), USER))
        .isInstanceOf(UploadService.FileNotFound.class);
  }

  @Test
  void huyPhien() throws Exception {
    UploadSession s = open(pdf(10), "application/pdf", "C1", false);
    service.cancel(s.id(), USER);
    service.cancel(s.id(), USER);
    assertThat(sessionRows.get(s.id()).status()).isEqualTo(Status.HUY);
    verify(storage).abortMultipart(any(), eq("rgw-1"));
    assertThat(code(() -> service.uploadPart(s.id(), 1, 10, null, USER)))
        .isEqualTo("FILE-409-UPLOAD-CLOSED");
    assertThat(service.view(s.id(), USER).receivedParts()).isEmpty();
  }

  @Test
  void hoanTatGhiKhoaVaPhatSuKien() throws Exception {
    byte[] content = pdf(1000);
    UploadSession s = open(content, "application/pdf", "C2", false);
    stubAssembled(content);

    UploadService.Completed done = service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER);
    StoredFile f = done.file();
    assertThat(done.duplicate()).isFalse();
    assertThat(f.objectId()).isEqualTo(s.id());
    assertThat(f.bucket()).isEqualTo("ev-dev-" + TENANT);
    assertThat(f.lockUntil()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    assertThat(sessionRows.get(s.id()).status()).isEqualTo(Status.HOAN_TAT);
    assertThat(events).containsExactly(f);
    verify(storage).deleteStaging(ObjectLocation.staging("dev", TENANT, s.id()));
    verify(storage).ensureLockedBucket("ev-dev-" + TENANT, 1);

    // Gọi lại: cùng đối tượng, không phát thêm sự kiện.
    assertThat(service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER)).isEqualTo(done);
    assertThat(events).hasSize(1);
  }

  @Test
  void cungBamTroVeBanVatLyCuVaKeoDaiKhoa() throws Exception {
    byte[] content = pdf(1000);
    stubAssembled(content);
    UploadSession first = open(content, "application/pdf", "C1", false);
    StoredFile a = service.complete(first.id(), List.of(new Part(1, 0, "e1")), USER).file();
    // Bản cũ có hạn khoá ngắn hơn hạn của minh chứng mới.
    fileRows.put(a.objectId(), withLock(a, NOW.minus(Duration.ofDays(1))));
    UploadSession second = open(content, "application/pdf", "C1", false);
    UploadService.Completed b = service.complete(second.id(), List.of(new Part(1, 0, "e1")), USER);

    assertThat(b.duplicate()).isTrue();
    assertThat(b.file().objectKey()).isEqualTo(a.objectKey());
    assertThat(b.file().cephVersionId()).isEqualTo(a.cephVersionId());
    assertThat(b.file().lockUntil()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    verify(storage).copyLocked(any(), any(), any(), any());
    verify(storage).extendLock(a.location(), a.cephVersionId(), NOW.plus(Duration.ofDays(1)));
    assertThat(refCounts).containsValue(2);
  }

  @Test
  void hoanTatTuChoiKhiThieuPhanSaiBamSaiKieu() throws Exception {
    byte[] content = pdf(1000);
    UploadSession s = open(content, "application/pdf", "C1", false);
    when(storage.listParts(any(), eq("rgw-1"))).thenReturn(List.of());
    assertThat(code(() -> service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER)))
        .isEqualTo("FILE-422-MISSING-PARTS");
    when(storage.listParts(any(), eq("rgw-1"))).thenReturn(List.of(new Part(1, 999, "e1")));
    assertThat(code(() -> service.complete(s.id(), List.of(new Part(1, 0, "\"e2\"")), USER)))
        .isEqualTo("FILE-422-MISSING-PARTS");
    assertThat(code(() -> service.complete(s.id(), List.of(new Part(1, 0, "\"e1\"")), USER)))
        .isEqualTo("FILE-422-SIZE-MISMATCH");

    byte[] png = new byte[1000];
    png[0] = (byte) 0x89;
    stubAssembled(png);
    UploadSession wrongHash = open(content, "application/pdf", "C1", false);
    assertThat(code(() -> service.complete(wrongHash.id(), List.of(new Part(1, 0, "e1")), USER)))
        .isEqualTo("FILE-422-HASH-MISMATCH");
    assertThat(sessionRows.get(wrongHash.id()).status()).isEqualTo(Status.HUY);

    UploadSession wrongType = open(png, "application/pdf", "C1", false);
    assertThat(code(() -> service.complete(wrongType.id(), List.of(new Part(1, 0, "e1")), USER)))
        .isEqualTo("FILE-422-CONTENT-TYPE-MISMATCH");
    verify(storage, never()).copyLocked(any(), any(), any(), any());
  }

  @Test
  void banMaC3KhongKiemKieu() throws Exception {
    byte[] cipher = new byte[700];
    UploadSession s = open(cipher, "application/octet-stream", "C3", true);
    stubAssembled(cipher);
    UploadService.Completed done = service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER);
    assertThat(done.file().e2ee()).isTrue();
    assertThat(done.duplicate()).isNull();
    assertThat(refCounts).isEmpty();
  }

  @Test
  void multipartDaMatThi409() throws Exception {
    byte[] content = pdf(1000);
    UploadSession s = open(content, "application/pdf", "C1", false);
    when(storage.listParts(any(), eq("rgw-1"))).thenReturn(List.of(new Part(1, 1000, "e1")));
    doThrow(new UploadGoneException("x", null))
        .when(storage)
        .completeMultipart(any(), any(), any());
    assertThat(code(() -> service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER)))
        .isEqualTo("FILE-409-UPLOAD-CLOSED");
  }

  @Test
  void urlTaiChiChoTepSach() throws Exception {
    byte[] content = pdf(1000);
    UploadSession s = open(content, "application/pdf", "C1", false);
    stubAssembled(content);
    StoredFile f = service.complete(s.id(), List.of(new Part(1, 0, "e1")), USER).file();
    assertThat(code(() -> service.downloadUrl(f.objectId(), null, false)))
        .isEqualTo("FILE-409-NOT-CLEAN");
    StoredFile clean =
        new StoredFile(
            f.objectId(),
            f.versionId(),
            1,
            f.tenantId(),
            f.createdBy(),
            f.ownerModule(),
            f.ownerRefId(),
            f.mediaType(),
            f.classification(),
            f.sizeBytes(),
            f.sha256(),
            f.bucket(),
            f.objectKey(),
            f.cephVersionId(),
            f.lockUntil(),
            "SACH",
            f.createdAt());
    fileRows.put(f.objectId(), clean);
    when(storage.presignGet(any(), eq("v1"), eq(Duration.ofMinutes(5)), any(), eq("attachment")))
        .thenReturn(URI.create("http://rgw/x"));
    UploadService.DownloadLink link = service.downloadUrl(f.objectId(), null, true);
    assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    verify(guard, org.mockito.Mockito.times(2)).checkDownload(any());
    assertThatThrownBy(() -> service.downloadUrl(UUID.randomUUID(), null, false))
        .isInstanceOf(UploadService.FileNotFound.class);
    assertThat(new FileAccessGuard.FileAccess(clean).toAuthzResource().type()).isEqualTo("file");
  }

  @Test
  void donPhienQuaHan() throws Exception {
    UploadSession s = open(pdf(10), "application/pdf", "C1", false);
    sessionRows.put(
        s.id(),
        new UploadSession(
            s.id(),
            s.tenantId(),
            s.userId(),
            s.rgwUploadId(),
            s.declaredSha256(),
            s.declaredSize(),
            s.partSize(),
            0,
            NOW.minusSeconds(1),
            Status.MO,
            s.kind(),
            s.classification(),
            s.ownerModule(),
            s.ownerRefId(),
            s.fileNameEnc(),
            s.dekId()));
    assertThat(service.expireStale(10)).isEqualTo(1);
    assertThat(sessionRows.get(s.id()).status()).isEqualTo(Status.HUY);
    verify(storage).abortMultipart(any(), eq("rgw-1"));
    assertThat(code(() -> service.uploadPart(s.id(), 1, 10, null, USER)))
        .isEqualTo("FILE-409-UPLOAD-CLOSED");
  }

  @Test
  void thamSoCauHinh() {
    assertThatThrownBy(() -> new UploadService.Settings("dev", 0, PART, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new UploadService.Settings("dev", 201L * 1024 * 1024, PART, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UploadService.Settings("dev", 1, PART, Duration.ZERO, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @SuppressWarnings("unchecked")
  void stubAssembled(byte[] content) throws Exception {
    when(storage.listParts(any(), eq("rgw-1")))
        .thenReturn(List.of(new Part(1, content.length, "\"e1\"")));
    when(storage.stream(any(), isNull(), any()))
        .thenAnswer(
            inv ->
                ((ObjectStorage.StreamReader<Object>) inv.getArgument(2))
                    .read(new ByteArrayInputStream(content)));
    when(storage.copyLocked(any(), any(), any(), any()))
        .thenAnswer(inv -> new StoredObject(inv.getArgument(1), "v1", inv.getArgument(3)));
  }

  static String code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    try {
      call.call();
    } catch (BusinessException e) {
      return e.errorCode().code();
    } catch (Throwable t) {
      return t.toString();
    }
    return "không lỗi";
  }

  static byte[] pdf(int size) {
    byte[] b = new byte[size];
    byte[] head = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(head, 0, b, 0, Math.min(size, head.length));
    return b;
  }

  static String sha(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}
