package vn.viettel.okrkpi.evidence.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.Part;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadPart;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * {@code PUT /files/uploads/{uploadId}/parts/{n}} ({@code uploadFilePart}, {@code products.yaml}):
 * viết tay vì giao diện sinh đặt thân octet-stream thành {@code String}, tức đọc cả phần vào bộ nhớ
 * và hỏng dữ liệu nhị phân. Thân được chuyển theo luồng tới kho với độ dài từ {@code
 * Content-Length}. Gửi lại cùng phần ghi đè phần cũ nên an toàn mà không cần lưu khoá {@code
 * Idempotency-Key} (header vẫn bắt buộc theo hợp đồng).
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class FilePartController {

  private final UploadService uploads;

  public FilePartController(UploadService uploads) {
    this.uploads = uploads;
  }

  @PutMapping(
      value = "/files/uploads/{uploadId}/parts/{n}",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
  public ResponseEntity<FileUploadPart> uploadFilePart(
      @PathVariable("uploadId") UUID uploadId,
      @PathVariable("n") @Min(1) @Max(10000) Integer n,
      @RequestHeader(value = "Idempotency-Key") @Size(max = 64) String idempotencyKey,
      HttpServletRequest request)
      throws IOException {
    long length = request.getContentLengthLong();
    if (length < 0) {
      throw new BusinessException("COMMON-422-VALIDATION", "Phần tệp phải có Content-Length.");
    }
    Part p =
        uploads.uploadPart(uploadId, n, length, request.getInputStream(), CurrentCaller.actor());
    return ResponseEntity.ok(FilesController.part(p));
  }
}
