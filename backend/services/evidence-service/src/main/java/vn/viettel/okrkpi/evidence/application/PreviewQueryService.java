package vn.viettel.okrkpi.evidence.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.PreviewQueue.State;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.evidence.domain.PageSize;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.audit.C2Access;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * {@code getFilePreview}: cửa sổ trang ảnh xem trước với URL ký 5 phút, kiểm quyền và ghi nhật ký
 * truy cập như {@code getFileDownloadUrl}.
 */
public class PreviewQueryService {

  static final Duration URL_TTL = Duration.ofMinutes(5);

  /** Gợi ý giây chờ khi ảnh chưa dựng xong ({@code Retry-After}). */
  public static final int RETRY_AFTER_SECONDS = 3;

  public record PageLink(int page, URI url, int width, int height) {}

  /** {@code status} theo hợp đồng: DANG_TAO, SAN_SANG, KHONG_HO_TRO, LOI. */
  public record Preview(
      String status, Integer pageCount, byte[] sha256, List<PageLink> pages, Instant expiresAt) {
    public boolean pending() {
      return "DANG_TAO".equals(status);
    }
  }

  private final PreviewQueue queue;
  private final ObjectStorage storage;
  private final FileAccessGuard guard;
  private final TransactionOperations tx;
  private final Clock clock;

  public PreviewQueryService(
      PreviewQueue queue,
      ObjectStorage storage,
      FileAccessGuard guard,
      TransactionOperations tx,
      Clock clock) {
    this.queue = queue;
    this.storage = storage;
    this.guard = guard;
    this.tx = tx;
    this.clock = clock;
  }

  @C2Access(objectType = "TEP_MINH_CHUNG")
  public Preview preview(UUID objectId, Integer versionNo, int fromPage, int count) {
    State s =
        tx.execute(t -> queue.state(objectId, versionNo))
            .orElseThrow(UploadService.FileNotFound::new);
    StoredFile f = s.file();
    guard.checkPreview(new FileAccessGuard.FileAccess(f));
    if (f.e2ee()) {
      return new Preview("KHONG_HO_TRO", null, f.sha256(), List.of(), null);
    }
    if (!"SACH".equals(f.scanStatus())) {
      throw new BusinessException("FILE-409-NOT-CLEAN");
    }
    if (!"SAN_SANG".equals(s.status())) {
      String status = "LOI".equals(s.status()) ? "LOI" : "DANG_TAO";
      return new Preview(status, null, f.sha256(), List.of(), null);
    }
    Instant expires = clock.instant().plus(URL_TTL).truncatedTo(ChronoUnit.SECONDS);
    List<PageLink> links = new ArrayList<>();
    int last = Math.min(s.pages().size(), fromPage + count - 1);
    for (int n = fromPage; n <= last; n++) {
      PageSize size = s.pages().get(n - 1);
      URI url =
          storage.presignGet(
              ObjectLocation.preview(f.bucket(), f.tenantId(), f.versionId(), n),
              null,
              URL_TTL,
              "image/png",
              "inline");
      links.add(new PageLink(n, url, size.width(), size.height()));
    }
    return new Preview("SAN_SANG", s.pages().size(), f.sha256(), links, expires);
  }
}
