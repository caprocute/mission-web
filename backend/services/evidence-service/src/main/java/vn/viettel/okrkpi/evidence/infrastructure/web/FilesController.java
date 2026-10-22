package vn.viettel.okrkpi.evidence.infrastructure.web;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.evidence.infrastructure.web.api.FilesApi;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileDownloadUrl;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileObject;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FilePreview;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileScanResult;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadComplete;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadCreate;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadPart;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadSession;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.RescanFileObject202Response;

/**
 * Chín thao tác {@code /files/**} của {@code products.yaml} (x-mission.owner SVC-08), sinh giao
 * diện từ hợp đồng (API-first). DV-08-01 chỉ dựng khung: mọi thao tác trả 501 cho tới khi DV-08-02
 * (tải lên nhiều phần, URL tải), DV-08-04 (quét) và DV-08-06 (xem trước) hiện thực.
 */
@RestController
public class FilesController implements FilesApi {

  @Override
  public ResponseEntity<Void> cancelFileUpload(UUID uploadId, String idempotencyKey) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileObject> completeFileUpload(
      UUID uploadId, String idempotencyKey, FileUploadComplete fileUploadComplete) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileUploadSession> createFileUpload(
      String idempotencyKey, FileUploadCreate fileUploadCreate) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileDownloadUrl> getFileDownloadUrl(
      UUID objectId, Integer version, String disposition) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FilePreview> getFilePreview(
      UUID objectId, Integer version, Integer fromPage, Integer count) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileScanResult> getFileScanResult(UUID objectId, Integer version) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileUploadSession> getFileUpload(UUID uploadId) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<RescanFileObject202Response> rescanFileObject(
      String idempotencyKey, UUID objectId) {
    throw notImplemented();
  }

  @Override
  public ResponseEntity<FileUploadPart> uploadFilePart(
      UUID uploadId, Integer n, String idempotencyKey, String body) {
    throw notImplemented();
  }

  private static ResponseStatusException notImplemented() {
    return new ResponseStatusException(
        HttpStatus.NOT_IMPLEMENTED, "Chức năng chưa được triển khai");
  }
}
