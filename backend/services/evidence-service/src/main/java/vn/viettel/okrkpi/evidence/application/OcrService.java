package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Đọc chữ minh chứng nền qua ai-gateway (DV-08-05, TL-01 "Kiến trúc cổng mô hình"; QT-07 SCR-0702,
 * BR-07-09): việc được xếp sau khi quét sạch tệp ảnh hoặc PDF không phải C3; worker gọi {@code POST
 * /ai/ocr}, lưu văn bản đã mã hoá cùng phiên bản. Ảnh không đủ nét ghi {@code KHONG_DOC_DUOC} để
 * giao diện báo MSG-07-06; lỗi tạm thời thử lại có giới hạn rồi {@code LOI}; lỗi đọc chữ không bao
 * giờ làm hỏng minh chứng. Tệp C3 bị chặn ở cả lúc xếp việc lẫn lúc gọi (ADR-06).
 */
public class OcrService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrService.class);

  /** Kiểu tệp ai-gateway nhận đọc chữ (lược đồ {@code AiOcrRequest.mimeType}). */
  public static final Set<String> OCR_MEDIA_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");

  public record Settings(Duration lease, Duration retryBase, int maxAttempts) {}

  private final OcrQueue queue;
  private final OcrGateway gateway;
  private final OcrTextCipher cipher;
  private final TransactionOperations tx;
  private final Settings settings;
  private final Clock clock;

  public OcrService(
      OcrQueue queue,
      OcrGateway gateway,
      OcrTextCipher cipher,
      TransactionOperations tx,
      Settings settings,
      Clock clock) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Tệp cần đọc chữ: ảnh hoặc PDF, không phải C3. */
  public static boolean needsOcr(StoredFile f) {
    return !f.e2ee() && OCR_MEDIA_TYPES.contains(f.mediaType());
  }

  /** Khoá đọc gửi ai-gateway: {@code <tenant>/<đối tượng>/<phiên bản>}, không lộ khoá vật lý. */
  public static String objectKey(StoredFile f) {
    return f.tenantId() + "/" + f.objectId() + "/" + f.versionNo();
  }

  /** Đọc một lô việc đến hạn; trả số việc đã lấy. */
  public int readDue(int limit) {
    List<Due> due = tx.execute(t -> queue.due(limit));
    for (Due d : due) {
      try {
        TenantContext.run(TenantId.of(d.tenantId()), () -> readOne(d.versionId()));
      } catch (RuntimeException e) {
        LOG.error("Không ghi được kết quả đọc chữ phiên bản {}", d.versionId(), e);
      }
    }
    return due.size();
  }

  void readOne(UUID versionId) {
    Claim claim = tx.execute(t -> queue.claim(versionId, settings.lease())).orElse(null);
    if (claim == null) {
      return;
    }
    StoredFile f = claim.file();
    if (!needsOcr(f)) {
      // Lớp chặn thứ hai: tệp C3 hay kiểu khác lọt vào hàng đợi cũng không được gửi đi.
      tx.execute(t -> queue.failed(versionId));
      return;
    }
    try {
      OcrGateway.Result r =
          gateway.read(
              new OcrGateway.Request(
                  objectKey(f),
                  HexFormat.of().formatHex(f.sha256()),
                  f.mediaType(),
                  f.classification()),
              "ocr-" + versionId + "-" + claim.attempt());
      OcrTextCipher.Sealed sealed = cipher.seal(f.tenantId(), versionId, r.fullText());
      tx.execute(
          t ->
              queue.done(
                  versionId,
                  new OcrQueue.Text(
                      sealed.ciphertext(),
                      sealed.dekId(),
                      r.modelId(),
                      r.pageCount(),
                      r.processedAt() == null ? clock.instant() : r.processedAt())));
      LOG.info("Đọc chữ phiên bản {}: {} trang", versionId, r.pageCount());
    } catch (OcrGateway.UnreadableException e) {
      LOG.info("Tệp phiên bản {} không đủ nét để đọc chữ", versionId);
      tx.execute(t -> queue.unreadable(versionId));
    } catch (OcrGateway.RefusedException e) {
      LOG.warn("ai-gateway từ chối đọc chữ phiên bản {}: {}", versionId, e.getMessage());
      tx.execute(t -> queue.failed(versionId));
    } catch (IOException | RuntimeException e) {
      retryOrFail(claim, e);
    }
  }

  private void retryOrFail(Claim claim, Exception e) {
    UUID versionId = claim.file().versionId();
    if (claim.attempt() >= settings.maxAttempts()) {
      LOG.error(
          "Đọc chữ phiên bản {} lỗi sau {} lần thử: {}",
          versionId,
          claim.attempt(),
          e.getClass().getSimpleName());
      tx.execute(t -> queue.failed(versionId));
      return;
    }
    Duration wait = settings.retryBase().multipliedBy(1L << Math.min(claim.attempt() - 1, 16));
    LOG.warn(
        "Đọc chữ phiên bản {} lỗi, thử lại sau {}: {}",
        versionId,
        wait,
        e.getClass().getSimpleName());
    tx.executeWithoutResult(t -> queue.retryAt(versionId, clock.instant().plus(wait)));
  }
}
