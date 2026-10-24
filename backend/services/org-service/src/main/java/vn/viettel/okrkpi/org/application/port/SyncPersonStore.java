package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-07 · Đọc và ghi {@code org.person} cho bộ so khớp đồng bộ; cột C2 chỉ đi qua dạng mã hoá. */
public interface SyncPersonStore {

  /** Phiên bản hiện hành (tại {@code day}) của một người, kèm cột C2 dạng mã. */
  record PersonRow(UUID id, String fullName, UUID dekId, byte[] birthDateEnc, byte[] nationalIdEnc,
      String employmentStatus, boolean partyMember) {
  }

  /** Người mới từ nguồn; cột C2 đã mã hoá với {@code dekId}. */
  record NewPerson(UUID versionId, UUID id, String fullName, UUID dekId, byte[] birthDateEnc, byte[] nationalIdEnc,
      byte[] nationalIdBidx, boolean partyMember, String employmentStatus, LocalDate validFrom) {
  }

  List<PersonRow> findByNationalIdIndex(byte[] bidx, LocalDate day);

  List<PersonRow> findByFullName(String fullName, LocalDate day);

  Optional<PersonRow> findById(UUID id, LocalDate day);

  void insert(NewPerson person, Actor actor);

  /** Như {@link #insert(NewPerson, Actor)}, kèm giới tính ({@code NAM}, {@code NU}, {@code KHAC}; null là chưa rõ). */
  void insert(NewPerson person, String gender, Actor actor);

  /** Ghi phiên bản mới từ {@code from} với họ tên mới, như {@link #changeFrom}. Trả {@code sourceVersion} mới. */
  int renameFrom(UUID id, LocalDate from, String fullName, Actor actor);

  /**
   * Ghi phiên bản mới từ {@code from} với trạng thái công tác hoặc đảng tịch mới (null là giữ nguyên); phiên bản cũ
   * bị đóng trên trục ghi nhận, không ghi đè. Trả {@code sourceVersion} mới.
   */
  int changeFrom(UUID id, LocalDate from, String employmentStatus, Boolean partyMember, Actor actor);

  /** {@code max(row_version)} của người. */
  int entityVersion(UUID id);
}
