package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.io.OutputStream;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cấp nội dung tệp cho ai-gateway đọc chữ (DV-08-05) theo khoá {@code <tenant>/<đối tượng>/<phiên
 * bản>}: chỉ phiên bản đã quét {@code SACH}, kiểu ảnh hoặc PDF và không phải C3 (lớp chặn thứ hai
 * sau hàng đợi, ADR-06). Nội dung đọc thẳng từ kho (SSE-C do SVC-08 giữ khoá) nên ai-gateway không
 * cần quyền vào Ceph.
 */
public class OcrContentService {

  /** Từ chối cấp nội dung (C3, chưa quét sạch hoặc kiểu không đọc chữ). */
  public static class DeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeniedException(String message) {
      super(message);
    }
  }

  private final StoredFiles files;
  private final ObjectStorage storage;
  private final TransactionOperations tx;

  public OcrContentService(StoredFiles files, ObjectStorage storage, TransactionOperations tx) {
    this.files = Objects.requireNonNull(files, "files");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  /**
   * Kiểm tra quyền đọc rồi trả tệp; ném {@link DeniedException} hoặc {@link
   * NoSuchElementException}.
   */
  public StoredFile authorize(UUID tenantId, UUID objectId, int versionNo) {
    Object[] out = new Object[1];
    TenantContext.run(
        TenantId.of(tenantId),
        () -> out[0] = tx.execute(t -> files.find(objectId, versionNo)).orElse(null));
    StoredFile f =
        Optional.ofNullable((StoredFile) out[0])
            .filter(x -> x.tenantId().equals(tenantId))
            .orElseThrow(() -> new NoSuchElementException("Không có tệp"));
    if (f.e2ee()) {
      throw new DeniedException("Tệp mã hoá đầu cuối không được gửi đọc chữ");
    }
    if (!"SACH".equals(f.scanStatus())) {
      throw new DeniedException("Tệp chưa quét sạch");
    }
    if (!OcrService.OCR_MEDIA_TYPES.contains(f.mediaType())) {
      throw new DeniedException("Kiểu tệp không đọc chữ được");
    }
    return f;
  }

  /** Chép nội dung tệp đã được {@link #authorize} ra luồng đích. */
  public void copy(StoredFile f, OutputStream out) throws IOException {
    storage.stream(f.location(), f.cephVersionId(), in -> in.transferTo(out));
  }
}
