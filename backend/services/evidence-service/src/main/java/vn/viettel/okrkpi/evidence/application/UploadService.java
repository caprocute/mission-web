package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.Part;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.StoredObject;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.UploadGoneException;
import vn.viettel.okrkpi.evidence.domain.ContentInspector;
import vn.viettel.okrkpi.evidence.domain.ContentInspector.Inspection;
import vn.viettel.okrkpi.evidence.domain.FileKind;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.evidence.domain.UploadPlan;
import vn.viettel.okrkpi.evidence.domain.UploadSession;
import vn.viettel.okrkpi.evidence.domain.UploadSession.Status;
import vn.viettel.okrkpi.shared.audit.C2Access;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tải tệp minh chứng nhiều phần (DV-08-02, QT-07, SCR-0701): mở phiên, nhận từng phần theo luồng,
 * tải tiếp, huỷ, hoàn tất có kiểm băm (BR-07-02) và kiểu thật (SEC-48), ghi kho Object Lock
 * (BR-07-01), phát {@code sys.evidence.object.stored.v1}, cấp URL tải 5 phút.
 *
 * <p>Phần tải lên nằm ở bucket tạm của cell; chỉ khi băm và kiểu đúng, tệp mới được sao chép phía
 * kho sang bucket khoá của tenant với khoá theo nội dung. Lệnh tới kho không chạy trong giao dịch
 * CSDL để không giữ kết nối khi chép tệp lớn.
 */
public class UploadService {

  static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

  private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);
  private static final String UPLOAD_CLOSED = "FILE-409-UPLOAD-CLOSED";
  private static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  private static final String VALIDATION = "COMMON-422-VALIDATION";

  /** Phiên hoặc đối tượng không có trong tenant hiện tại (404 chuẩn {@code common.yaml}). */
  public static class FileNotFound extends RuntimeException {
    public FileNotFound() {
      super("Không tìm thấy tệp hoặc phiên tải lên.");
    }
  }

  /** Tham số tải lên (SEC-48, TL-01 SVC-08). */
  public record Settings(
      String cell, long maxFileBytes, int partSize, Duration sessionTtl, int lockDays) {

    /** Trần cấu hình theo tenant của SEC-48. */
    public static final long HARD_LIMIT = 200L * 1024 * 1024;

    public Settings {
      Objects.requireNonNull(cell, "cell");
      if (maxFileBytes < 1 || maxFileBytes > HARD_LIMIT) {
        throw new IllegalArgumentException("Giới hạn tệp phải từ 1 byte đến 200 MB");
      }
      new UploadPlan(1, partSize);
      if (lockDays < 1) {
        throw new IllegalArgumentException("Thời hạn khoá phải từ 1 ngày");
      }
    }
  }

  /** Lệnh mở phiên (hợp đồng {@code FileUploadCreate}). */
  public record CreateUpload(
      String fileName,
      String mediaType,
      long declaredSize,
      String declaredSha256Hex,
      String ownerModule,
      UUID ownerRefId,
      String classification,
      boolean e2ee) {}

  /** Phiên kèm các phần đã nhận. */
  public record SessionView(UploadSession session, List<Part> receivedParts) {}

  /** URL tải có hạn. */
  public record DownloadLink(URI url, Instant expiresAt, StoredFile file) {}

  private final UploadSessions sessions;
  private final StoredFiles files;
  private final FileEvents events;
  private final FileNameCipher names;
  private final ObjectStorage storage;
  private final TenantBucketService buckets;
  private final FileAccessGuard guard;
  private final TransactionOperations tx;
  private final Settings settings;
  private final Clock clock;

  /**
   * Kết quả hoàn tất: {@code duplicate} cho biết trong tenant đã có minh chứng khác cùng băm
   * (BR-07-03, MSG-07-01); {@code null} với tệp C3 vì không khử trùng bản mã.
   */
  public record Completed(StoredFile file, Boolean duplicate) {}

  static final Duration LOCK_SLACK = Duration.ofDays(1);

  public UploadService(
      UploadSessions sessions,
      StoredFiles files,
      FileEvents events,
      FileNameCipher names,
      ObjectStorage storage,
      TenantBucketService buckets,
      FileAccessGuard guard,
      TransactionOperations tx,
      Settings settings,
      Clock clock) {
    this.sessions = sessions;
    this.files = files;
    this.events = events;
    this.names = names;
    this.storage = storage;
    this.buckets = buckets;
    this.guard = guard;
    this.tx = tx;
    this.settings = settings;
    this.clock = clock;
  }

  /** {@code createFileUpload}: kiểm kiểu và kích thước (SEC-48), mở multipart ở bucket tạm. */
  @RequiresPermission(operationId = "createFileUpload")
  public UploadSession create(CreateUpload cmd, UUID actor) {
    UUID tenant = TenantContext.require().value();
    FileKind kind =
        FileKind.ofMediaType(cmd.mediaType())
            .orElseThrow(() -> new BusinessException("FILE-415-UNSUPPORTED-TYPE"));
    boolean c3 = "C3".equals(cmd.classification());
    if (c3 != cmd.e2ee()) {
      throw new BusinessException(
          VALIDATION, "Tệp mật phải tải lên dưới dạng đã mã hoá đầu cuối và ngược lại.");
    }
    if (kind == FileKind.CIPHERTEXT && !c3) {
      throw new BusinessException("FILE-415-UNSUPPORTED-TYPE");
    }
    if (cmd.declaredSize() > settings.maxFileBytes()) {
      throw new BusinessException("FILE-413-TOO-LARGE");
    }
    byte[] sha = parseSha(cmd.declaredSha256Hex());
    UUID id = Ids.newUuidV7();
    String stagingBucket = ObjectLocation.stagingBucketOf(settings.cell());
    storage.ensureStagingBucket(stagingBucket, 2);
    String rgwUploadId =
        storage.createMultipart(
            ObjectLocation.staging(settings.cell(), tenant, id), cmd.mediaType());
    FileNameCipher.Sealed name = names.seal(tenant, id, cmd.fileName());
    UploadSession session =
        new UploadSession(
            id,
            tenant,
            actor,
            rgwUploadId,
            sha,
            cmd.declaredSize(),
            settings.partSize(),
            0,
            clock.instant().plus(settings.sessionTtl()).truncatedTo(ChronoUnit.MICROS),
            Status.MO,
            kind,
            cmd.classification(),
            cmd.ownerModule(),
            cmd.ownerRefId(),
            name.ciphertext(),
            name.dekId());
    tx.executeWithoutResult(s -> sessions.insert(session));
    return session;
  }

  /** {@code getFileUpload}: trạng thái và các phần đã nhận để tải tiếp. */
  @RequiresPermission(operationId = "getFileUpload")
  public SessionView view(UUID uploadId, UUID actor) {
    UploadSession s = owned(uploadId, actor);
    List<Part> parts = s.status() == Status.MO ? received(s) : List.of();
    return new SessionView(s, parts);
  }

  /**
   * {@code uploadFilePart}: chuyển luồng phần {@code n} thẳng tới kho, không đệm cả phần; gửi lại
   * cùng phần thì ghi đè.
   */
  @RequiresPermission(operationId = "uploadFilePart")
  public Part uploadPart(UUID uploadId, int n, long length, InputStream content, UUID actor) {
    UploadSession s = owned(uploadId, actor);
    requireOpen(s);
    long expected = s.plan().expectedSize(n);
    if (expected < 0) {
      throw new BusinessException(VALIDATION, "Số phần vượt quá số phần của tệp.");
    }
    if (length > s.partSize()) {
      throw new BusinessException("FILE-413-PART-TOO-LARGE");
    }
    if (length != expected) {
      throw new BusinessException("FILE-422-SIZE-MISMATCH");
    }
    String etag;
    try {
      etag = storage.uploadPart(staging(s), s.rgwUploadId(), n, content, length);
    } catch (UploadGoneException e) {
      throw new BusinessException(UPLOAD_CLOSED);
    } catch (ObjectStorage.PartInterruptedException e) {
      LOG.info("Phần {} của phiên {} bị cắt giữa chừng, chờ client gửi lại", n, uploadId);
      throw new BusinessException("FILE-422-SIZE-MISMATCH");
    }
    int done = received(s).size();
    tx.executeWithoutResult(t -> sessions.updatePartsDone(uploadId, done, actor));
    return new Part(n, length, etag);
  }

  /** {@code cancelFileUpload}: huỷ multipart và đặt {@code HUY}. */
  @RequiresPermission(operationId = "cancelFileUpload")
  public void cancel(UUID uploadId, UUID actor) {
    UploadSession s = owned(uploadId, actor);
    if (s.status() == Status.HUY) {
      return;
    }
    if (s.status() == Status.HOAN_TAT) {
      throw new BusinessException(UPLOAD_CLOSED);
    }
    Boolean closed = tx.execute(t -> sessions.close(uploadId, Status.HUY, actor));
    if (!Boolean.TRUE.equals(closed)) {
      throw new BusinessException(UPLOAD_CLOSED);
    }
    storage.abortMultipart(staging(s), s.rgwUploadId());
  }

  /**
   * {@code completeFileUpload}: ghép phần, tính lại SHA-256 và kiểu thật, sao chép sang bucket
   * khoá, ghi siêu dữ liệu cùng outbox trong một giao dịch. Gọi lại sau khi đã xong trả cùng đối
   * tượng (mã đối tượng trùng mã phiên).
   */
  @RequiresPermission(operationId = "completeFileUpload")
  public Completed complete(UUID uploadId, List<Part> declaredParts, UUID actor) {
    UploadSession s = owned(uploadId, actor);
    if (s.status() == Status.HOAN_TAT) {
      return existing(uploadId);
    }
    requireOpen(s);
    ObjectLocation staging = staging(s);
    List<Part> parts = checkParts(s, declaredParts);
    try {
      storage.completeMultipart(staging, s.rgwUploadId(), parts);
    } catch (UploadGoneException e) {
      return replayOrClosed(uploadId);
    }
    Inspection found = inspect(staging, s);
    String problem = mismatch(s, found);
    if (problem != null) {
      storage.deleteStaging(staging);
      tx.executeWithoutResult(t -> sessions.close(uploadId, Status.HUY, actor));
      throw new BusinessException(problem);
    }
    buckets.provision(s.tenantId());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant lockUntil = now.plus(settings.lockDays(), ChronoUnit.DAYS);
    // Bản mã C3 không khử trùng: cùng nội dung rõ vẫn ra bản mã khác nhau.
    boolean dedupe = !s.e2ee();
    StoredObject stored =
        (dedupe
                ? tx.execute(t -> files.physicalCopy(found.sha256()))
                : Optional.<StoredObject>empty())
            .map(copy -> reuse(copy, lockUntil))
            .orElseGet(
                () ->
                    storage.copyLocked(
                        staging,
                        ObjectLocation.of(settings.cell(), s.tenantId(), found.sha256()),
                        s.kind().mediaType(),
                        lockUntil));
    storage.deleteStaging(staging);
    StoredFile file =
        new StoredFile(
            uploadId,
            Ids.newUuidV7(),
            1,
            s.tenantId(),
            actor,
            s.ownerModule(),
            s.ownerRefId(),
            s.kind().mediaType(),
            s.classification(),
            found.size(),
            found.sha256(),
            stored.location().bucket(),
            stored.location().key(),
            stored.versionId(),
            stored.lockedUntil(),
            "CHO_QUET",
            now);
    Integer refs =
        tx.execute(
            t -> {
              if (!sessions.close(uploadId, Status.HOAN_TAT, actor)) {
                return null;
              }
              files.insert(file, s.fileNameEnc(), s.dekId());
              events.stored(file);
              return dedupe ? files.registerReference(file.sha256(), file.objectKey(), actor) : 0;
            });
    if (refs == null) {
      return replayOrClosed(uploadId);
    }
    return new Completed(file, dedupe ? refs > 1 : null);
  }

  /**
   * Trỏ phiên bản mới vào bản vật lý cùng băm đã có; hạn khoá được kéo dài nếu ngắn hơn hạn của
   * minh chứng mới quá {@link #LOCK_SLACK}, để mỗi minh chứng giữ đủ thời hạn lưu giữ của mình.
   */
  private StoredObject reuse(StoredObject copy, Instant lockUntil) {
    // Chỉ kéo dài khi thiếu quá một ngày: tránh một lệnh S3 cho mỗi lần dùng lại.
    if (!copy.lockedUntil().isBefore(lockUntil.minus(LOCK_SLACK))) {
      return copy;
    }
    storage.extendLock(copy.location(), copy.versionId(), lockUntil);
    return new StoredObject(copy.location(), copy.versionId(), lockUntil);
  }

  /**
   * {@code getFileDownloadUrl}: OPA kiểm quyền theo bản ghi sở hữu tệp, nhật ký truy cập ghi khi
   * trả về, URL ký trước sống 5 phút cho đúng một phiên bản. Không trả tệp chưa quét sạch (trừ bản
   * mã C3 không quét ở máy chủ).
   */
  @C2Access(objectType = "TEP_MINH_CHUNG")
  public DownloadLink downloadUrl(UUID objectId, Integer versionNo, boolean attachment) {
    StoredFile f =
        tx.execute(t -> files.find(objectId, versionNo)).orElseThrow(() -> new FileNotFound());
    guard.checkDownload(new FileAccessGuard.FileAccess(f));
    if (!f.e2ee() && !"SACH".equals(f.scanStatus())) {
      throw new BusinessException("FILE-409-NOT-CLEAN");
    }
    Instant expires = clock.instant().plus(DOWNLOAD_TTL).truncatedTo(ChronoUnit.SECONDS);
    URI url =
        storage.presignGet(
            f.location(),
            f.cephVersionId(),
            DOWNLOAD_TTL,
            f.mediaType(),
            attachment ? "attachment" : "inline");
    return new DownloadLink(url, expires, f);
  }

  /** Job dọn: huỷ multipart dở dang của phiên quá hạn, đặt {@code HUY}. Trả số phiên đã dọn. */
  public int expireStale(int limit) {
    List<UploadSessions.Expired> stale = tx.execute(t -> sessions.expired(limit));
    int done = 0;
    for (UploadSessions.Expired e : stale) {
      try {
        TenantContext.run(
            vn.viettel.okrkpi.shared.kernel.TenantId.of(e.tenantId()),
            () -> {
              Optional<UploadSession> s = tx.execute(t -> sessions.find(e.id()));
              if (s.isEmpty()) {
                return;
              }
              Boolean closed =
                  tx.execute(t -> sessions.close(e.id(), Status.HUY, s.get().userId()));
              if (Boolean.TRUE.equals(closed)) {
                storage.abortMultipart(staging(s.get()), s.get().rgwUploadId());
              }
            });
        done++;
      } catch (RuntimeException ex) {
        LOG.warn("Không dọn được phiên tải lên quá hạn {}: {}", e.id(), ex.toString());
      }
    }
    return done;
  }

  private UploadSession owned(UUID uploadId, UUID actor) {
    UploadSession s =
        tx.execute(t -> sessions.find(uploadId)).orElseThrow(() -> new FileNotFound());
    if (!s.userId().equals(actor)) {
      throw new BusinessException(OUT_OF_SCOPE);
    }
    return s;
  }

  private void requireOpen(UploadSession s) {
    if (!s.openAt(clock.instant())) {
      throw new BusinessException(UPLOAD_CLOSED);
    }
  }

  private ObjectLocation staging(UploadSession s) {
    return ObjectLocation.staging(settings.cell(), s.tenantId(), s.id());
  }

  private List<Part> received(UploadSession s) {
    try {
      return storage.listParts(staging(s), s.rgwUploadId());
    } catch (UploadGoneException e) {
      return List.of();
    }
  }

  /** Mọi phần phải có trên kho, đúng ETag client giữ, đủ số phần và đúng tổng kích thước. */
  private List<Part> checkParts(UploadSession s, List<Part> declared) {
    Map<Integer, Part> onStore =
        received(s).stream().collect(Collectors.toMap(Part::partNo, Function.identity()));
    int count = s.plan().partCount();
    if (declared.size() != count || onStore.size() < count) {
      throw new BusinessException("FILE-422-MISSING-PARTS");
    }
    long total = 0;
    for (Part p : declared) {
      Part stored = onStore.get(p.partNo());
      if (stored == null || p.partNo() > count || !sameEtag(stored.etag(), p.etag())) {
        throw new BusinessException("FILE-422-MISSING-PARTS");
      }
      total += stored.size();
    }
    if (total != s.declaredSize()) {
      throw new BusinessException("FILE-422-SIZE-MISMATCH");
    }
    return declared.stream()
        .map(p -> onStore.get(p.partNo()))
        .sorted(Comparator.comparingInt(Part::partNo))
        .toList();
  }

  private static boolean sameEtag(String a, String b) {
    return a != null && b != null && strip(a).equals(strip(b));
  }

  private static String strip(String etag) {
    return etag.replace("\"", "");
  }

  private Inspection inspect(ObjectLocation staging, UploadSession s) {
    try {
      return storage.stream(staging, null, in -> ContentInspector.inspect(in, !s.e2ee()));
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được tệp vừa ghép", e);
    }
  }

  /** Mã lỗi khi tệp ghép khác khai báo, hoặc {@code null} khi khớp. */
  private static String mismatch(UploadSession s, Inspection found) {
    if (found.size() != s.declaredSize()) {
      return "FILE-422-SIZE-MISMATCH";
    }
    if (!java.security.MessageDigest.isEqual(found.sha256(), s.declaredSha256())) {
      return "FILE-422-HASH-MISMATCH";
    }
    if (!s.e2ee() && !found.matches(s.kind())) {
      return "FILE-422-CONTENT-TYPE-MISMATCH";
    }
    return null;
  }

  private Completed existing(UUID objectId) {
    return tx.execute(
            t ->
                files
                    .find(objectId, null)
                    .map(
                        f ->
                            new Completed(
                                f,
                                f.e2ee() ? null : files.hasOtherCopy(f.sha256(), f.versionId()))))
        .orElseThrow(() -> new BusinessException(UPLOAD_CLOSED));
  }

  /** Lệnh song song đã hoàn tất phiên thì trả kết quả của nó; phiên đã huỷ thì 409. */
  private Completed replayOrClosed(UUID uploadId) {
    return existing(uploadId);
  }

  private static byte[] parseSha(String hex) {
    if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) {
      throw new BusinessException(VALIDATION, "Mã băm SHA-256 phải gồm 64 ký tự thập lục phân.");
    }
    return HexFormat.of().parseHex(hex.toLowerCase(java.util.Locale.ROOT));
  }
}
