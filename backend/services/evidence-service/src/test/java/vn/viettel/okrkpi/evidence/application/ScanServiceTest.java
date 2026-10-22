package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.ScanVerdict;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

class ScanServiceTest {

  static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");

  final Map<UUID, StoredFile> files = new HashMap<>();
  final Map<UUID, Integer> attempts = new HashMap<>();
  final Map<UUID, Instant> nextAt = new HashMap<>();
  final List<ScanRecord> results = new ArrayList<>();
  final List<String> events = new ArrayList<>();
  final Map<UUID, byte[]> content = new HashMap<>();
  MalwareScanner.Finding finding = new MalwareScanner.Finding(false, null);
  boolean scannerDown;

  final ScanQueue queue =
      new ScanQueue() {
        @Override
        public List<Due> due(int limit) {
          return files.values().stream()
              .filter(f -> "CHO_QUET".equals(f.scanStatus()))
              .map(f -> new Due(f.versionId(), f.tenantId()))
              .toList();
        }

        @Override
        public Optional<Claim> claim(UUID versionId, Duration lease) {
          StoredFile f = files.get(versionId);
          if (!"CHO_QUET".equals(f.scanStatus())) {
            return Optional.empty();
          }
          return Optional.of(new Claim(f, attempts.merge(versionId, 1, Integer::sum)));
        }

        @Override
        public void retryAt(UUID versionId, Instant at) {
          nextAt.put(versionId, at);
        }

        @Override
        public boolean record(ScanRecord r) {
          StoredFile f = files.get(r.versionId());
          files.put(r.versionId(), withStatus(f, r.verdict().scanStatus()));
          results.add(r);
          return true;
        }

        @Override
        public Optional<Status> status(UUID objectId, Integer versionNo) {
          return Optional.empty();
        }

        @Override
        public long pending() {
          return due(500).size();
        }
      };

  final ObjectStorage storage =
      new ObjectStorage() {
        @Override
        public <T> T stream(ObjectLocation location, String versionId, StreamReader<T> reader)
            throws IOException {
          byte[] b = content.get(UUID.fromString(versionId));
          return reader.read(new ByteArrayInputStream(b));
        }

        // Các thao tác còn lại không dùng tới khi quét.
        @Override
        public boolean ensureLockedBucket(String bucket, int lockDays) {
          return true;
        }

        @Override
        public void ensureStagingBucket(String bucket, int expireDays) {}

        @Override
        public StoredObject put(
            ObjectLocation location, byte[] c, String mediaType, Instant lockUntil) {
          return null;
        }

        @Override
        public byte[] read(ObjectLocation location, String versionId) {
          return null;
        }

        @Override
        public String createMultipart(ObjectLocation location, String mediaType) {
          return null;
        }

        @Override
        public String uploadPart(
            ObjectLocation location, String uploadId, int partNo, InputStream c, long length) {
          return null;
        }

        @Override
        public List<Part> listParts(ObjectLocation location, String uploadId) {
          return List.of();
        }

        @Override
        public void completeMultipart(ObjectLocation location, String uploadId, List<Part> parts) {}

        @Override
        public void abortMultipart(ObjectLocation location, String uploadId) {}

        @Override
        public StoredObject copyLocked(
            ObjectLocation from, ObjectLocation to, String mediaType, Instant lockUntil) {
          return null;
        }

        @Override
        public void extendLock(ObjectLocation location, String versionId, Instant lockUntil) {}

        @Override
        public void deleteStaging(ObjectLocation location) {}

        @Override
        public URI presignGet(
            ObjectLocation location,
            String versionId,
            Duration ttl,
            String mediaType,
            String contentDisposition) {
          return null;
        }
      };

  final MalwareScanner scanner =
      new MalwareScanner() {
        @Override
        public Finding scan(InputStream in) {
          if (scannerDown) {
            throw new ScannerUnavailableException("tắt", null);
          }
          return finding;
        }

        @Override
        public String version() {
          if (scannerDown) {
            throw new ScannerUnavailableException("tắt", null);
          }
          return "1.4.6/28157";
        }

        @Override
        public String engine() {
          return "ClamAV";
        }
      };

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final ScanService service =
      new ScanService(
          queue,
          new ScanEvents() {
            @Override
            public void scanned(StoredFile file, ScanRecord result) {
              events.add("scanned:" + result.verdict());
            }

            @Override
            public void quarantined(StoredFile file, ScanRecord result) {
              events.add("quarantined");
            }
          },
          storage,
          scanner,
          TransactionOperations.withoutTransaction(),
          new ScanService.Settings(Duration.ofMinutes(10), Duration.ofSeconds(30), 3),
          Clock.fixed(NOW, ZoneOffset.UTC),
          meters);

  StoredFile add(String mediaType, String cls, byte[] body, byte[] recordedSha) throws Exception {
    UUID v = UUID.randomUUID();
    byte[] sha =
        recordedSha != null ? recordedSha : MessageDigest.getInstance("SHA-256").digest(body);
    StoredFile f =
        new StoredFile(
            UUID.randomUUID(),
            v,
            1,
            TENANT,
            UUID.randomUUID(),
            "product",
            null,
            mediaType,
            cls,
            body.length,
            sha,
            "b",
            "k",
            v.toString(),
            NOW,
            "CHO_QUET",
            NOW.minusSeconds(3));
    files.put(v, f);
    content.put(v, body);
    return f;
  }

  static StoredFile withStatus(StoredFile f, String status) {
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
        f.lockUntil(),
        status,
        f.createdAt());
  }

  static byte[] pdf() {
    return "%PDF-1.7\nnoi dung".getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void sachNhiemVaC3() throws Exception {
    StoredFile clean = add("application/pdf", "C1", pdf(), null);
    assertThat(service.scanDue(10)).isEqualTo(1);
    assertThat(files.get(clean.versionId()).scanStatus()).isEqualTo("SACH");
    assertThat(results.get(0).engineVersion()).isEqualTo("1.4.6/28157");

    finding = new MalwareScanner.Finding(true, "Eicar Test\"Signature");
    StoredFile bad = add("application/pdf", "C1", (pdf().length + "x").getBytes(), null);
    content.put(bad.versionId(), "%PDF-1.7 khac".getBytes(StandardCharsets.US_ASCII));
    files.put(
        bad.versionId(),
        new StoredFile(
            bad.objectId(),
            bad.versionId(),
            1,
            TENANT,
            bad.createdBy(),
            "product",
            null,
            "application/pdf",
            "C1",
            13,
            MessageDigest.getInstance("SHA-256").digest(content.get(bad.versionId())),
            "b",
            "k",
            bad.versionId().toString(),
            NOW,
            "CHO_QUET",
            NOW));
    service.scanDue(10);
    assertThat(files.get(bad.versionId()).scanStatus()).isEqualTo("NHIEM");
    assertThat(results.get(1).detail()).isEqualTo("{\"mau\":\"Eicar_Test_Signature\"}");
    assertThat(events).containsExactly("scanned:SACH", "scanned:NHIEM", "quarantined");

    StoredFile c3 = add("application/octet-stream", "C3", new byte[5], null);
    service.scanDue(10);
    assertThat(results.get(2).engine()).isEqualTo(ScanService.DEVICE_ENGINE);
    assertThat(files.get(c3.versionId()).scanStatus()).isEqualTo("SACH");
    assertThat(meters.timer("evidence.scan.latency").count()).isEqualTo(3);
    assertThat(meters.get("evidence.scan.pending").gauge().value()).isZero();
  }

  @Test
  void saiDinhDangVaSaiBamLaNghiNgo() throws Exception {
    StoredFile png = add("application/pdf", "C1", new byte[] {(byte) 0x89, 'P', 'N', 'G'}, null);
    StoredFile tampered = add("application/pdf", "C1", pdf(), new byte[32]);
    service.scanDue(10);
    assertThat(results).extracting(ScanRecord::verdict).containsOnly(ScanVerdict.NGHI_NGO);
    assertThat(files.get(png.versionId()).scanStatus()).isEqualTo("NHIEM");
    assertThat(files.get(tampered.versionId()).scanStatus()).isEqualTo("NHIEM");
    assertThat(results)
        .extracting(ScanRecord::detail)
        .containsExactlyInAnyOrder("{\"lyDo\":\"SAI_DINH_DANG\"}", "{\"lyDo\":\"SAI_BAM\"}");
  }

  @Test
  void boQuetLoiThuLaiCoGianCachRoiKetLuanLoi() throws Exception {
    scannerDown = true;
    StoredFile f = add("application/pdf", "C1", pdf(), null);
    service.scanDue(10);
    assertThat(nextAt.get(f.versionId())).isEqualTo(NOW.plusSeconds(30));
    service.scanDue(10);
    assertThat(nextAt.get(f.versionId())).isEqualTo(NOW.plusSeconds(60));
    service.scanDue(10);
    assertThat(files.get(f.versionId()).scanStatus()).isEqualTo("LOI");
    assertThat(meters.counter("evidence.scan.failures").count()).isEqualTo(1);
    assertThat(events).containsExactly("scanned:LOI");
  }
}
