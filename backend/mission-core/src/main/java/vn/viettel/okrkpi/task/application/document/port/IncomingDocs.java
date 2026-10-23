package vn.viettel.okrkpi.task.application.document.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Văn bản đến của đơn vị nhận ({@code task.incoming_doc}, bổ sung), khoá đoạn giao việc ({@code
 * task.incoming_doc_claim}, BR-05-07) và liên kết văn bản–nhiệm vụ ({@code task.task_source_doc}).
 */
public interface IncomingDocs {

  /** Một đoạn nội dung của văn bản. */
  record Paragraph(int no, String text) {}

  /** Siêu dữ liệu mang trong EVT-24. */
  record Received(
      UUID id,
      String sourceSystem,
      String connectorCode,
      String externalId,
      String docNumber,
      LocalDate docDate,
      String issuer,
      String excerpt,
      String objectKey,
      int docVersion,
      UUID eventId,
      Instant receivedAt) {}

  /** Toàn văn và đơn vị nhận lấy từ SVC-13. */
  record Content(
      UUID recipientUnitId,
      String format,
      String issuerHeader,
      String title,
      String preamble,
      List<Paragraph> paragraphs,
      String recipients,
      String classification) {

    public Content {
      paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
    }
  }

  /** Một văn bản đến đã lưu. */
  record DocRow(
      UUID id,
      UUID recipientUnitId,
      String sourceSystem,
      String connectorCode,
      String externalId,
      String docNumber,
      LocalDate docDate,
      String issuer,
      String excerpt,
      String format,
      String issuerHeader,
      String title,
      String preamble,
      List<Paragraph> paragraphs,
      String recipients,
      String classification,
      boolean contentLoaded,
      int docVersion,
      UUID eventId,
      Instant receivedAt,
      String status,
      String dismissReason,
      long rowVersion) {

    public DocRow {
      paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
    }
  }

  /** Vị trí con trỏ của danh sách: thời điểm nhận giảm dần rồi mã giảm dần. */
  record After(Instant receivedAt, UUID id) {}

  /** Đoạn đã sinh hoặc gộp nhiệm vụ. */
  record Claim(int paragraphNo, UUID taskId, int periodBucket, String taskCode, String kind) {}

  /** Liên kết văn bản–nhiệm vụ cần ghi. */
  record Link(
      UUID periodId,
      int periodBucket,
      UUID taskId,
      DocRow doc,
      int paragraphNo,
      boolean aiExtracted,
      UUID suggestionId,
      List<String> kept,
      List<String> edited,
      String similarReason) {}

  /** Liên kết của một nhiệm vụ tới văn bản đến. */
  record Linked(
      UUID docId,
      UUID recipientUnitId,
      int paragraphNo,
      boolean aiExtracted,
      String similarReason) {}

  /**
   * Ghi văn bản lần đầu hoặc cập nhật siêu dữ liệu khi phiên bản mới hơn; giữ tình trạng xử lý và
   * nội dung đã lấy.
   */
  void upsertReceived(TenantId tenant, Received received);

  void storeContent(TenantId tenant, UUID id, Content content);

  Optional<DocRow> find(TenantId tenant, UUID id);

  List<DocRow> list(TenantId tenant, UUID unitId, String status, int limit, After after);

  /** Chuyển {@code CHO_XU_LY → DA_BO_QUA}; {@code false} khi văn bản không còn chờ xử lý. */
  boolean dismiss(TenantId tenant, UUID id, String reason, UUID actor, Instant at);

  /** Chuyển {@code CHO_XU_LY → DA_XU_LY} khi đã có nhiệm vụ đầu tiên. */
  void markProcessed(TenantId tenant, UUID id, UUID actor, Instant at);

  /** Khoá đoạn; rỗng khi khoá được, ngược lại trả khoá đã có. */
  Optional<Claim> claim(
      TenantId tenant,
      UUID docId,
      int paragraphNo,
      UUID taskId,
      int periodBucket,
      String taskCode,
      String kind,
      UUID actor);

  List<Claim> claims(TenantId tenant, UUID docId);

  void link(TenantId tenant, Link link, UUID actor);

  Optional<Linked> linkOf(TenantId tenant, int periodBucket, UUID taskId);

  /** Nhiệm vụ đích của thao tác gộp hoặc giao theo lô. */
  record TaskRef(
      UUID id,
      UUID periodId,
      int periodBucket,
      String code,
      String title,
      String status,
      String source,
      UUID orgUnitId,
      long rowVersion) {}

  Optional<TaskRef> task(TenantId tenant, UUID taskId);
}
