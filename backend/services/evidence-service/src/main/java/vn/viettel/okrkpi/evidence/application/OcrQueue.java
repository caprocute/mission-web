package vn.viettel.okrkpi.evidence.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;

/**
 * Hàng đợi đọc chữ (OCR) theo phiên bản đối tượng (DV-08-05, cột {@code ocr_*} của {@code
 * object_version}): xếp việc sau khi quét sạch, worker nhận việc có hạn thuê, ghi văn bản đã mã hoá
 * vào {@code ocr_text}.
 */
public interface OcrQueue {

  /** Văn bản đọc được, đã mã hoá phong bì. */
  record Text(byte[] textEnc, UUID dekId, String modelId, int pageCount, Instant processedAt) {}

  List<Due> due(int limit);

  Optional<Claim> claim(UUID versionId, Duration lease);

  void retryAt(UUID versionId, Instant at);

  /** {@code CHO_DOC} → {@code DA_DOC} cùng văn bản. */
  boolean done(UUID versionId, Text text);

  /** {@code CHO_DOC} → {@code KHONG_DOC_DUOC} (ảnh không đủ nét, MSG-07-06). */
  boolean unreadable(UUID versionId);

  /** {@code CHO_DOC} → {@code LOI}. */
  boolean failed(UUID versionId);

  /** Trạng thái đọc chữ của phiên bản; rỗng khi không xếp việc. */
  Optional<String> status(UUID versionId);
}
