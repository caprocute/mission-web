package vn.viettel.okrkpi.evidence.application;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/**
 * Kho {@code evidence.object_meta}, {@code evidence.object_version} và {@code evidence.dedup_index}
 * (RLS theo tenant: mọi tra cứu băm chỉ thấy tenant hiện hành).
 */
public interface StoredFiles {

  /** Ghi đối tượng mới cùng phiên bản đầu; {@code fileNameEnc}, {@code dekId} là tên tệp C2. */
  void insert(StoredFile file, byte[] fileNameEnc, UUID dekId);

  /** Phiên bản {@code versionNo}, hoặc phiên bản hiện hành khi rỗng. */
  Optional<StoredFile> find(UUID objectId, Integer versionNo);

  /** Bản vật lý đã lưu đầu tiên có cùng băm trong tenant, để phiên bản mới trỏ tới (khử trùng). */
  Optional<ObjectStorage.StoredObject> physicalCopy(byte[] sha256);

  /**
   * Ghi một tham chiếu vào {@code dedup_index}: dòng mới khi băm chưa có, nếu có thì tăng {@code
   * ref_count} và thêm {@code owner} vào {@code owners_sample} khi còn chỗ (tối đa 16). Yêu cầu
   * đồng thời cùng băm gộp về một dòng ({@code ON CONFLICT}).
   *
   * @return {@code ref_count} sau khi ghi
   */
  int registerReference(byte[] sha256, String objectKey, UUID owner);

  /** Có phiên bản khác cùng băm trong tenant (dùng khi trả lại kết quả của phiên đã xong). */
  boolean hasOtherCopy(byte[] sha256, UUID versionId);
}
