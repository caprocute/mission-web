package vn.viettel.okrkpi.evidence.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.MalwareScanner.Finding;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.ContentInspector;
import vn.viettel.okrkpi.evidence.domain.ContentInspector.Inspection;
import vn.viettel.okrkpi.evidence.domain.FileKind;
import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.ScanVerdict;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker quét mã độc ở nền (SEC-47) và kiểm lại định dạng (SEC-48) cho mỗi phiên bản {@code
 * CHO_QUET}: nhận việc, đọc đối tượng từ kho, gửi ClamAV, ghi {@code scan_result} cùng sự kiện
 * trong một giao dịch. Bộ quét lỗi thì thử lại có giãn cách, quá {@link Settings#maxAttempts()} lần
 * thì kết luận {@code LOI} và báo vận hành. Tệp C3 không quét ở máy chủ (đã quét trên thiết bị
 * trước khi mã hoá): ghi kết luận {@code SACH} với bộ máy {@link #DEVICE_ENGINE} để ghi rõ không áp
 * dụng.
 */
public class ScanService {

  private static final Logger LOG = LoggerFactory.getLogger(ScanService.class);

  public static final String DEVICE_ENGINE = "thiet-bi";

  /**
   * @param lease hạn thuê một việc, đủ dài cho tệp lớn nhất
   * @param retryBase giãn cách lần thử lại đầu, nhân đôi mỗi lần
   * @param maxAttempts số lần nhận việc tối đa trước khi kết luận {@code LOI}
   */
  public record Settings(Duration lease, Duration retryBase, int maxAttempts) {
    public Settings {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts phải ≥ 1");
      }
    }
  }

  private final ScanQueue queue;
  private final ScanEvents events;
  private final ObjectStorage storage;
  private final MalwareScanner scanner;
  private final TransactionOperations tx;
  private final Settings settings;
  private final Clock clock;
  private final Timer latency;
  private final Counter failures;

  public ScanService(
      ScanQueue queue,
      ScanEvents events,
      ObjectStorage storage,
      MalwareScanner scanner,
      TransactionOperations tx,
      Settings settings,
      Clock clock,
      MeterRegistry meters) {
    this.queue = queue;
    this.events = events;
    this.storage = storage;
    this.scanner = scanner;
    this.tx = tx;
    this.settings = settings;
    this.clock = clock;
    this.latency =
        Timer.builder("evidence.scan.latency")
            .description("Từ lúc lưu xong tới lúc có kết luận quét")
            .publishPercentiles(0.95)
            .register(meters);
    this.failures =
        Counter.builder("evidence.scan.failures")
            .description("Phiên bản kết luận LOI vì bộ quét lỗi quá số lần thử")
            .register(meters);
    meters.gauge("evidence.scan.pending", queue, q -> tx.execute(t -> q.pending()));
  }

  /** Quét một lô việc đến hạn; trả số việc đã lấy để job biết còn việc. */
  public int scanDue(int limit) {
    var due = tx.execute(t -> queue.due(limit));
    for (Due d : due) {
      try {
        TenantContext.run(TenantId.of(d.tenantId()), () -> scanOne(d.versionId()));
      } catch (RuntimeException e) {
        // Lỗi CSDL khi ghi kết quả: việc còn CHO_QUET, hết hạn thuê thì được nhận lại.
        LOG.error("Không ghi được kết quả quét phiên bản {}", d.versionId(), e);
      }
    }
    return due.size();
  }

  void scanOne(UUID versionId) {
    Claim claim = tx.execute(t -> queue.claim(versionId, settings.lease())).orElse(null);
    if (claim == null) {
      return;
    }
    StoredFile f = claim.file();
    if (f.e2ee()) {
      finish(f, DEVICE_ENGINE, "khong-ap-dung", ScanVerdict.SACH, "{\"apDung\":false}");
      return;
    }
    try {
      String version = scanner.version();
      Inspection format = read(f, in -> ContentInspector.inspect(in, true));
      if (!java.security.MessageDigest.isEqual(format.sha256(), f.sha256())) {
        // Nội dung trên kho khác băm đã ghi lúc nhận: không cấp cho người dùng.
        finish(f, scanner.engine(), version, ScanVerdict.NGHI_NGO, "{\"lyDo\":\"SAI_BAM\"}");
        return;
      }
      if (!format.matches(FileKind.ofMediaType(f.mediaType()).orElse(null))) {
        finish(
            f,
            scanner.engine(),
            version,
            ScanVerdict.NGHI_NGO,
            format.hasMacro() ? "{\"lyDo\":\"MACRO\"}" : "{\"lyDo\":\"SAI_DINH_DANG\"}");
        return;
      }
      Finding found = read(f, scanner::scan);
      if (found.infected()) {
        finish(f, scanner.engine(), version, ScanVerdict.NHIEM, signature(found.signature()));
      } else {
        finish(f, scanner.engine(), version, ScanVerdict.SACH, null);
      }
    } catch (RuntimeException e) {
      // Bộ quét lỗi, đọc kho lỗi: trả việc lại để thử sau (lỗi ghi CSDL ở finish cũng vậy).
      failed(claim, e);
    }
  }

  private void failed(Claim claim, RuntimeException e) {
    StoredFile f = claim.file();
    if (claim.attempt() >= settings.maxAttempts()) {
      LOG.error(
          "Quét phiên bản {} lỗi sau {} lần thử, kết luận LOI: {}",
          f.versionId(),
          claim.attempt(),
          e.getMessage());
      failures.increment();
      finish(f, scanner.engine(), "khong-xac-dinh", ScanVerdict.LOI, "{\"lyDo\":\"BO_QUET_LOI\"}");
      return;
    }
    Duration wait = settings.retryBase().multipliedBy(1L << Math.min(claim.attempt() - 1, 16));
    LOG.warn(
        "Quét phiên bản {} lỗi lần {}, thử lại sau {}: {}",
        f.versionId(),
        claim.attempt(),
        wait,
        e.getMessage());
    tx.executeWithoutResult(t -> queue.retryAt(f.versionId(), clock.instant().plus(wait)));
  }

  private void finish(
      StoredFile f, String engine, String version, ScanVerdict verdict, String detail) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ScanRecord r =
        new ScanRecord(Ids.newUuidV7(), f.versionId(), engine, version, verdict, detail, now);
    boolean recorded =
        Boolean.TRUE.equals(
            tx.execute(
                t -> {
                  if (!queue.record(r)) {
                    return false;
                  }
                  events.scanned(f, r);
                  if (verdict.quarantined()) {
                    events.quarantined(f, r);
                  }
                  return true;
                }));
    if (recorded) {
      latency.record(Duration.between(f.createdAt(), now));
    }
  }

  private <T> T read(StoredFile f, ObjectStorage.StreamReader<T> reader) {
    try {
      return storage.stream(f.location(), f.cephVersionId(), reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Tên mẫu mã độc chỉ gồm ký tự an toàn (dữ liệu từ bộ quét, không tin cậy). */
  private static String signature(String name) {
    String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._:-]", "_");
    return "{\"mau\":\"" + safe.substring(0, Math.min(safe.length(), 200)) + "\"}";
  }
}
