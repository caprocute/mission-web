package vn.viettel.okrkpi.evidence.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.StoredObject;
import vn.viettel.okrkpi.evidence.domain.ContentInspector;
import vn.viettel.okrkpi.evidence.domain.ContentInspector.Inspection;
import vn.viettel.okrkpi.evidence.domain.FileKind;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Nhận tệp từ dịch vụ khác thay cho trình duyệt (CORE-03-04, BR-07-13): integration-gateway chuyển
 * tệp gốc của văn bản đã ban hành trên vOffice sang kho minh chứng. Cùng các bước như hoàn tất tải
 * lên: kiểm kiểu thật và kích thước (SEC-48), tính SHA-256, ghi kho Object Lock (BR-07-01), ghi
 * siêu dữ liệu cùng outbox {@code sys.evidence.object.stored.v1}, rồi tệp vào hàng đợi quét như mọi
 * tệp khác. Không nhận tệp C3 (bản mã chỉ đi từ trình duyệt theo ADR-06).
 *
 * <p>Mã đối tượng suy ra từ tenant và khoá chống ghi trùng, nên gọi lại cùng khoá trả cùng đối
 * tượng. Lệnh tới kho không chạy trong giao dịch CSDL.
 */
public class IngestService {

  /** Mức dữ liệu của văn bản đã ban hành dùng làm minh chứng. */
  static final String CLASSIFICATION = "C2";

  static final String OWNER_MODULE = "product";

  /** Lệnh nhận tệp. */
  public record Ingest(
      UUID tenantId,
      String idempotencyKey,
      UUID ownerRefId,
      UUID requestedBy,
      String fileName,
      String mediaType,
      byte[] content) {

    public Ingest {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(ownerRefId, "ownerRefId");
      Objects.requireNonNull(requestedBy, "requestedBy");
      Objects.requireNonNull(content, "content");
      if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
        throw new IllegalArgumentException("Thiếu khoá chống ghi trùng hợp lệ");
      }
    }
  }

  private final StoredFiles files;
  private final FileEvents events;
  private final FileNameCipher names;
  private final ObjectStorage storage;
  private final TenantBucketService buckets;
  private final TransactionOperations tx;
  private final UploadService.Settings settings;
  private final Clock clock;

  public IngestService(
      StoredFiles files,
      FileEvents events,
      FileNameCipher names,
      ObjectStorage storage,
      TenantBucketService buckets,
      TransactionOperations tx,
      UploadService.Settings settings,
      Clock clock) {
    this.files = Objects.requireNonNull(files, "files");
    this.events = Objects.requireNonNull(events, "events");
    this.names = Objects.requireNonNull(names, "names");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.buckets = Objects.requireNonNull(buckets, "buckets");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Mã đối tượng của một lệnh nhận: cố định theo tenant và khoá chống ghi trùng. */
  static UUID objectIdOf(UUID tenant, String idempotencyKey) {
    return UUID.nameUUIDFromBytes(
        ("ingest:" + tenant + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
  }

  public StoredFile ingest(Ingest cmd) {
    StoredFile[] out = new StoredFile[1];
    TenantContext.run(TenantId.of(cmd.tenantId()), () -> out[0] = store(cmd));
    return out[0];
  }

  private StoredFile store(Ingest cmd) {
    UUID objectId = objectIdOf(cmd.tenantId(), cmd.idempotencyKey());
    Optional<StoredFile> done = tx.execute(t -> files.find(objectId, null));
    if (done.isPresent()) {
      return done.get();
    }
    FileKind kind =
        FileKind.ofMediaType(cmd.mediaType())
            .filter(k -> k != FileKind.CIPHERTEXT)
            .orElseThrow(() -> new BusinessException("FILE-415-UNSUPPORTED-TYPE"));
    if (cmd.content().length == 0 || cmd.content().length > settings.maxFileBytes()) {
      throw new BusinessException("FILE-413-TOO-LARGE");
    }
    Inspection found;
    try {
      found = ContentInspector.inspect(new ByteArrayInputStream(cmd.content()), true);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!found.matches(kind)) {
      throw new BusinessException("FILE-422-CONTENT-TYPE-MISMATCH");
    }
    buckets.provision(cmd.tenantId());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant lockUntil = now.plus(settings.lockDays(), ChronoUnit.DAYS);
    StoredObject stored =
        tx.execute(t -> files.physicalCopy(found.sha256()))
            .map(copy -> reuse(copy, lockUntil))
            .orElseGet(
                () ->
                    storage.put(
                        ObjectLocation.of(settings.cell(), cmd.tenantId(), found.sha256()),
                        cmd.content(),
                        kind.mediaType(),
                        lockUntil));
    FileNameCipher.Sealed name =
        names.seal(
            cmd.tenantId(),
            objectId,
            cmd.fileName() == null || cmd.fileName().isBlank() ? "van-ban.pdf" : cmd.fileName());
    StoredFile file =
        new StoredFile(
            objectId,
            Ids.newUuidV7(),
            1,
            cmd.tenantId(),
            cmd.requestedBy(),
            OWNER_MODULE,
            cmd.ownerRefId(),
            kind.mediaType(),
            CLASSIFICATION,
            found.size(),
            found.sha256(),
            stored.location().bucket(),
            stored.location().key(),
            stored.versionId(),
            stored.lockedUntil(),
            "CHO_QUET",
            now);
    try {
      tx.executeWithoutResult(
          t -> {
            files.insert(file, name.ciphertext(), name.dekId());
            events.stored(file);
            files.registerReference(file.sha256(), file.objectKey(), cmd.requestedBy());
          });
    } catch (DuplicateKeyException e) {
      // Lệnh song song cùng khoá đã ghi trước.
      return tx.execute(t -> files.find(objectId, null)).orElseThrow(() -> e);
    }
    return file;
  }

  /** Bản vật lý cùng băm đã có: kéo dài khoá khi thiếu quá {@link UploadService#LOCK_SLACK}. */
  private StoredObject reuse(StoredObject copy, Instant lockUntil) {
    if (!copy.lockedUntil().isBefore(lockUntil.minus(UploadService.LOCK_SLACK))) {
      return copy;
    }
    storage.extendLock(copy.location(), copy.versionId(), lockUntil);
    return new StoredObject(copy.location(), copy.versionId(), lockUntil);
  }
}
