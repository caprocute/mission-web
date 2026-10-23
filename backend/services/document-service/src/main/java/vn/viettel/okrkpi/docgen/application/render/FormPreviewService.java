package vn.viettel.okrkpi.docgen.application.render;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfConverter;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Xem trước biểu mẫu từ dữ liệu hiện tại ({@code previewDossierForm}, SCR-0901 "Xem trước Mẫu 1B",
 * DV-11-02): sinh đồng bộ, mang dấu "Bản nháp", không tạo {@code render_output} hay {@code
 * form_instance}, không ghi tệp vào kho và không qua veraPDF (tệp không phải hồ sơ chính thức).
 *
 * <ul>
 *   <li>Giới hạn tốc độ theo người dùng ({@link PreviewRateLimiter}): vượt thì 429 kèm {@code
 *       Retry-After}.
 *   <li>Trộn và chuyển PDF chạy trên luồng riêng có giới hạn ({@code executor}); quá {@code
 *       timeout} hoặc hàng đợi đầy thì 503 kèm {@code Retry-After} để giao diện thử lại (suy luận
 *       về cách xử lý quá thời gian, wiki trang 14).
 * </ul>
 */
public class FormPreviewService {

  static final String DEPENDENCY = "docgen-preview";
  static final String INVALID = "DOSSIER-422-VALIDATION";

  private final RenderService render;
  private final RenderInputPort inputs;
  private final PdfConverter converter;
  private final PreviewRateLimiter limiter;
  private final Executor executor;
  private final Duration timeout;

  public FormPreviewService(
      RenderService render,
      RenderInputPort inputs,
      PdfConverter converter,
      PreviewRateLimiter limiter,
      Executor executor,
      Duration timeout) {
    this.render = render;
    this.inputs = inputs;
    this.converter = converter;
    this.limiter = limiter;
    this.executor = executor;
    this.timeout = timeout;
  }

  /** Yêu cầu xem trước của một người dùng. */
  public record PreviewCommand(
      UUID tenantId, UUID caller, String formCode, String sourceModule, UUID sourceRefId) {
    public PreviewCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(formCode, "formCode");
      Objects.requireNonNull(sourceModule, "sourceModule");
      Objects.requireNonNull(sourceRefId, "sourceRefId");
    }
  }

  /** Tệp PDF xem trước. */
  public byte[] preview(PreviewCommand command) {
    limiter.acquire(command.tenantId(), command.caller());
    RenderInputPort.PreviewData data =
        inputs
            .preview(
                command.tenantId(),
                command.caller(),
                command.formCode(),
                command.sourceModule(),
                command.sourceRefId())
            .orElseThrow(() -> new DocgenNotFoundException("Không tìm thấy dữ liệu để xem trước"));
    Template template =
        render.templateFor(command.formCode(), data.subjectGroupCode(), data.templateVersion());
    RenderContext ctx =
        new RenderContext(
            command.tenantId(),
            command.sourceRefId(),
            template.formCode(),
            template.versionNo(),
            data.policyPackCode(),
            data.policyPackVersion(),
            true);
    RenderStamp stamp =
        new RenderStamp(
            data.policyPackCode(),
            data.policyPackVersion(),
            template.formCode(),
            template.versionNo(),
            null,
            Hashes.sha256(data.canonicalJson()),
            null,
            true);
    CompletableFuture<byte[]> work;
    try {
      work =
          CompletableFuture.supplyAsync(
              () ->
                  converter.toPdfA2b(
                      render
                          .composer()
                          .compose(template, data.canonicalJson(), ctx, stamp.footerText()),
                      template.formCode() + ".docx"),
              executor);
    } catch (RejectedExecutionException e) {
      throw unavailable(DependencyUnavailableException.Reason.BULKHEAD_FULL, e);
    }
    try {
      return work.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      work.cancel(true);
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RejectedDocument rejected) {
        throw new BusinessException(INVALID, rejected.getMessage());
      }
      if (cause instanceof TransientFailure) {
        throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, cause);
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(cause);
    }
  }

  private DependencyUnavailableException unavailable(
      DependencyUnavailableException.Reason reason, Throwable cause) {
    return new DependencyUnavailableException(DEPENDENCY, reason, timeout, cause);
  }
}
