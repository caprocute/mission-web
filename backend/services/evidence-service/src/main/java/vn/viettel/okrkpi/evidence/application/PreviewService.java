package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.PreviewRendering.PageSink;
import vn.viettel.okrkpi.evidence.application.PreviewRendering.RenderFailure;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.FileKind;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.PageSize;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker dựng ảnh xem trước từng trang (getFilePreview) cho phiên bản đã quét sạch: tải tệp gốc về
 * thư mục tạm, DOCX, XLSX chuyển sang PDF qua Gotenberg, PDF dựng ảnh từng trang, ảnh gốc thu nhỏ
 * và mã hoá lại; ảnh trang ghi vào bucket có khoá của tệp gốc với cùng hạn khoá. Chạy cùng worker
 * quét và dùng chung hàng đợi co giãn. Tệp C3 không bao giờ được xếp việc.
 */
public class PreviewService {

  private static final Logger LOG = LoggerFactory.getLogger(PreviewService.class);

  /**
   * @param dpi độ phân giải dựng trang PDF (150 dpi cho trang A4 rộng 1240 điểm ảnh)
   * @param maxPages số trang dựng tối đa của một tệp
   * @param maxWidth chiều rộng tối đa của ảnh gốc sau khi thu nhỏ
   * @param workDir thư mục tạm chứa tệp gốc và PDF trung gian
   */
  public record Settings(
      Duration lease,
      Duration retryBase,
      int maxAttempts,
      float dpi,
      int maxPages,
      int maxWidth,
      Path workDir) {}

  private final PreviewQueue queue;
  private final ObjectStorage storage;
  private final PreviewRendering rendering;
  private final TransactionOperations tx;
  private final Settings settings;
  private final Clock clock;

  public PreviewService(
      PreviewQueue queue,
      ObjectStorage storage,
      PreviewRendering rendering,
      TransactionOperations tx,
      Settings settings,
      Clock clock) {
    this.queue = queue;
    this.storage = storage;
    this.rendering = rendering;
    this.tx = tx;
    this.settings = settings;
    this.clock = clock;
  }

  /** Dựng một lô việc đến hạn; trả số việc đã lấy. */
  public int renderDue(int limit) {
    List<Due> due = tx.execute(t -> queue.due(limit));
    for (Due d : due) {
      try {
        TenantContext.run(TenantId.of(d.tenantId()), () -> renderOne(d.versionId()));
      } catch (RuntimeException e) {
        LOG.error("Không ghi được kết quả dựng xem trước phiên bản {}", d.versionId(), e);
      }
    }
    return due.size();
  }

  void renderOne(UUID versionId) {
    Claim claim = tx.execute(t -> queue.claim(versionId, settings.lease())).orElse(null);
    if (claim == null) {
      return;
    }
    StoredFile f = claim.file();
    if (f.e2ee()) {
      tx.execute(t -> queue.failed(versionId));
      return;
    }
    List<Path> temps = new ArrayList<>();
    try {
      long started = System.nanoTime();
      List<PageSize> pages = render(f, temps);
      tx.execute(t -> queue.ready(versionId, pages));
      LOG.info(
          "Dựng xem trước phiên bản {}: {} trang trong {} ms",
          versionId,
          pages.size(),
          (System.nanoTime() - started) / 1_000_000);
    } catch (RenderFailure e) {
      if (e.transientFailure()) {
        retryOrFail(claim, e);
      } else {
        LOG.warn("Tệp phiên bản {} không dựng được xem trước: {}", versionId, e.getMessage());
        tx.execute(t -> queue.failed(versionId));
      }
    } catch (RuntimeException e) {
      retryOrFail(claim, e);
    } finally {
      for (Path p : temps) {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          LOG.warn("Không xoá được tệp tạm {}", p, e);
        }
      }
    }
  }

  private List<PageSize> render(StoredFile f, List<Path> temps) {
    FileKind kind =
        FileKind.ofMediaType(f.mediaType())
            .orElseThrow(() -> new RenderFailure("Kiểu tệp không xem trước được", false, null));
    Path source = temp(temps, "goc");
    try {
      storage.stream(
          f.location(),
          f.cephVersionId(),
          in -> Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<PageSize> pages = new ArrayList<>();
    PageSink sink =
        (n, png, w, h) -> {
          storage.put(
              ObjectLocation.preview(f.bucket(), f.tenantId(), f.versionId(), n),
              png,
              "image/png",
              f.lockUntil());
          pages.add(new PageSize(w, h));
        };
    switch (kind) {
      case PDF -> rendering.rasterizePdf(source, settings.dpi(), settings.maxPages(), sink);
      case DOCX, XLSX -> {
        Path pdf = temp(temps, "pdf");
        rendering.toPdf(source, "tep." + kind.name().toLowerCase(java.util.Locale.ROOT), pdf);
        rendering.rasterizePdf(pdf, settings.dpi(), settings.maxPages(), sink);
      }
      case JPEG, PNG -> rendering.scaleImage(source, settings.maxWidth(), sink);
      default -> throw new RenderFailure("Kiểu tệp không xem trước được", false, null);
    }
    if (pages.isEmpty()) {
      throw new RenderFailure("Tệp không có trang nào", false, null);
    }
    return pages;
  }

  private void retryOrFail(Claim claim, RuntimeException e) {
    UUID versionId = claim.file().versionId();
    if (claim.attempt() >= settings.maxAttempts()) {
      LOG.error(
          "Dựng xem trước phiên bản {} lỗi sau {} lần thử: {}",
          versionId,
          claim.attempt(),
          e.getMessage());
      tx.execute(t -> queue.failed(versionId));
      return;
    }
    Duration wait = settings.retryBase().multipliedBy(1L << Math.min(claim.attempt() - 1, 16));
    LOG.warn(
        "Dựng xem trước phiên bản {} lỗi, thử lại sau {}: {}", versionId, wait, e.getMessage());
    tx.executeWithoutResult(t -> queue.retryAt(versionId, clock.instant().plus(wait)));
  }

  private Path temp(List<Path> temps, String suffix) {
    try {
      Files.createDirectories(settings.workDir());
      Path p = Files.createTempFile(settings.workDir(), "xem-truoc-", "." + suffix);
      temps.add(p);
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
