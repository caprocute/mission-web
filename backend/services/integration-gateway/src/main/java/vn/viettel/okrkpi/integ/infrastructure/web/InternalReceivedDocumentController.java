package vn.viettel.okrkpi.integ.infrastructure.web;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.ReceivedDocumentReader;
import vn.viettel.okrkpi.integ.application.ReceivedDocumentReader.ReceivedDocument;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ cho MOD-02 dựng văn bản đến (QT-05 bước 1b, SCR-0502, CORE-02-16): toàn văn và đơn vị
 * nhận của văn bản đã phát EVT-24. Chỉ token dịch vụ vai trò {@link
 * InternalIssuedDocumentController#SERVICE_ROLE}; tenant do mission-core truyền (suy luận, hợp đồng
 * nội bộ chưa có trong {@code contracts/openapi}, như {@code /issued-docs}).
 */
@RestController
@RequestMapping("/internal/v1/integ")
@ConditionalOnWebApplication
public class InternalReceivedDocumentController {

  private final ReceivedDocumentReader reader;

  public InternalReceivedDocumentController(ReceivedDocumentReader reader) {
    this.reader = reader;
  }

  /** Một đoạn. */
  public record ParagraphDto(int no, String text) {}

  /** Toàn văn; tên trường khớp {@code HttpIncomingDocContents} của mission-core. */
  public record ReceivedDocDto(
      UUID recipientUnitId,
      String format,
      String issuerHeader,
      String title,
      String preamble,
      List<ParagraphDto> paragraphs,
      String recipients,
      String classification) {}

  @GetMapping("/received-docs/{documentId}")
  public ReceivedDocDto receivedDoc(@PathVariable UUID documentId, @RequestParam UUID tenantId) {
    ReceivedDocument d =
        reader
            .read(TenantId.of(tenantId), documentId)
            .orElseThrow(() -> new NoSuchElementException("Không có văn bản"));
    return new ReceivedDocDto(
        d.recipientUnitId(),
        d.content().format(),
        d.content().issuerHeader(),
        d.content().title(),
        d.content().preamble(),
        d.content().paragraphs().stream().map(p -> new ParagraphDto(p.no(), p.text())).toList(),
        d.content().recipients(),
        d.content().classification());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail unprocessable(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }
}
