package vn.viettel.okrkpi.evidence.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.Part;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.application.UploadService.CreateUpload;
import vn.viettel.okrkpi.evidence.application.UploadService.DownloadLink;
import vn.viettel.okrkpi.evidence.application.UploadService.SessionView;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.evidence.domain.UploadSession;
import vn.viettel.okrkpi.evidence.infrastructure.web.api.FilesApi;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileDownloadUrl;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileObject;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FilePreview;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileScanResult;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileScanStatus;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadComplete;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadCreate;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadPart;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadSession;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.FileUploadStatus;
import vn.viettel.okrkpi.evidence.infrastructure.web.model.RescanFileObject202Response;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;

/**
 * Thao tác {@code /files/**} của {@code products.yaml} (x-mission.owner SVC-08), giao diện sinh từ
 * hợp đồng (API-first). DV-08-02 hiện thực tải lên nhiều phần và URL tải; {@code uploadFilePart} ở
 * {@link FilePartController}. Quét (DV-08-04) và xem trước (DV-08-06) còn trả 501.
 *
 * <p>{@code completeFileUpload} không dùng {@link Idempotent}: thư viện chạy phương thức có khoá
 * trong một giao dịch, còn hoàn tất phải chép tệp lớn trên kho mà không giữ kết nối CSDL. Hoàn tất
 * tự bất biến theo phiên: gọi lại trả cùng đối tượng (mã đối tượng trùng mã phiên).
 */
@RestController
@RequestMapping("/api/v1")
public class FilesController implements FilesApi {

  private final UploadService uploads;

  public FilesController(UploadService uploads) {
    this.uploads = uploads;
  }

  @Override
  @Idempotent
  public ResponseEntity<FileUploadSession> createFileUpload(
      String idempotencyKey, FileUploadCreate body) {
    UploadSession s =
        uploads.create(
            new CreateUpload(
                body.getFileName(),
                body.getMediaType().getValue(),
                body.getDeclaredSize(),
                body.getDeclaredSha256(),
                body.getOwnerModule().getValue(),
                body.getOwnerRefId(),
                body.getClassification().getValue(),
                Boolean.TRUE.equals(body.getE2ee())),
            CurrentCaller.actor());
    return ResponseEntity.created(URI.create("/api/v1/files/uploads/" + s.id()))
        .body(session(new SessionView(s, java.util.List.of())));
  }

  @Override
  public ResponseEntity<FileUploadSession> getFileUpload(UUID uploadId) {
    return ResponseEntity.ok(session(uploads.view(uploadId, CurrentCaller.actor())));
  }

  @Override
  @Idempotent
  public ResponseEntity<Void> cancelFileUpload(UUID uploadId, String idempotencyKey) {
    uploads.cancel(uploadId, CurrentCaller.actor());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<FileObject> completeFileUpload(
      UUID uploadId, String idempotencyKey, FileUploadComplete body) {
    StoredFile f =
        uploads.complete(
            uploadId,
            body.getParts().stream()
                .map(
                    p ->
                        new Part(p.getPartNo(), p.getSize() == null ? 0 : p.getSize(), p.getEtag()))
                .toList(),
            CurrentCaller.actor());
    return ResponseEntity.ok(fileObject(f));
  }

  @Override
  public ResponseEntity<FileDownloadUrl> getFileDownloadUrl(
      UUID objectId, Integer version, String disposition) {
    DownloadLink link = uploads.downloadUrl(objectId, version, "attachment".equals(disposition));
    StoredFile f = link.file();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new FileDownloadUrl()
                .url(link.url())
                .expiresAt(offset(link.expiresAt()))
                .mediaType(f.mediaType())
                .sizeBytes(Math.toIntExact(f.sizeBytes()))
                .sha256(HexFormat.of().formatHex(f.sha256()))
                .e2ee(f.e2ee()));
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
  public ResponseEntity<RescanFileObject202Response> rescanFileObject(
      String idempotencyKey, UUID objectId) {
    throw notImplemented();
  }

  static FileUploadSession session(SessionView v) {
    UploadSession s = v.session();
    return new FileUploadSession()
        .uploadId(s.id())
        .status(FileUploadStatus.fromValue(s.status().name()))
        .partSize(s.partSize())
        .partCount(s.plan().partCount())
        .partsDone(v.receivedParts().isEmpty() ? s.partsDone() : v.receivedParts().size())
        .receivedParts(v.receivedParts().stream().map(FilesController::part).toList())
        .expiresAt(offset(s.expiresAt()));
  }

  static FileUploadPart part(Part p) {
    return new FileUploadPart().partNo(p.partNo()).size(Math.toIntExact(p.size())).etag(p.etag());
  }

  static FileObject fileObject(StoredFile f) {
    return new FileObject()
        .objectId(f.objectId())
        .objectVersionId(f.versionId())
        .versionNo(f.versionNo())
        .mediaType(f.mediaType())
        .sizeBytes(Math.toIntExact(f.sizeBytes()))
        .sha256(HexFormat.of().formatHex(f.sha256()))
        .classification(FileObject.ClassificationEnum.fromValue(f.classification()))
        .e2ee(f.e2ee())
        .scanStatus(FileScanStatus.fromValue(f.scanStatus()))
        .createdAt(offset(f.createdAt()));
  }

  private static OffsetDateTime offset(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private static ResponseStatusException notImplemented() {
    return new ResponseStatusException(
        HttpStatus.NOT_IMPLEMENTED, "Chức năng chưa được triển khai");
  }
}
