package vn.viettel.okrkpi.evidence.application;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** Kho {@code evidence.object_meta} và {@code evidence.object_version} (RLS theo tenant). */
public interface StoredFiles {

  /** Ghi đối tượng mới cùng phiên bản đầu; {@code fileNameEnc}, {@code dekId} là tên tệp C2. */
  void insert(StoredFile file, byte[] fileNameEnc, UUID dekId);

  /** Phiên bản {@code versionNo}, hoặc phiên bản hiện hành khi rỗng. */
  Optional<StoredFile> find(UUID objectId, Integer versionNo);
}
