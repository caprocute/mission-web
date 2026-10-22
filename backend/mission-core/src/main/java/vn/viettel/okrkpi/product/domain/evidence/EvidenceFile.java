package vn.viettel.okrkpi.product.domain.evidence;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Một tệp của phiên bản minh chứng ({@code product.evidence_version_file}) đối chiếu với bản chiếu
 * đối tượng SVC-08 ({@code product.evidence_object}).
 *
 * @param objectId đối tượng ở SVC-08
 * @param ordinal thứ tự hiển thị 1..20
 * @param recordedVersion phiên bản đối tượng ghi nhận khi gắn vào nháp
 * @param recordedSha256 mã băm ghi nhận khi gắn vào nháp
 * @param storedVersion phiên bản đối tượng hiện có ở SVC-08 ({@code null} khi chưa nhận sự kiện
 *     lưu)
 * @param storedSha256 mã băm hiện có ở SVC-08
 * @param scanStatus kết quả quét của tệp ({@code CHO_QUET}, {@code SACH}, {@code NHIEM}, {@code
 *     LOI})
 * @param duplicateOfVersionId phiên bản minh chứng của nhiệm vụ khác có tệp trùng mã băm
 * @param reuseJustification giải trình dùng lại tệp trùng
 */
public record EvidenceFile(
    UUID objectId,
    int ordinal,
    int recordedVersion,
    byte[] recordedSha256,
    Integer storedVersion,
    byte[] storedSha256,
    String scanStatus,
    UUID duplicateOfVersionId,
    String reuseJustification) {

  /** Độ dài tối thiểu và tối đa của giải trình dùng lại (BR-07-03). */
  public static final int JUSTIFICATION_MIN = 20;

  public static final int JUSTIFICATION_MAX = 1000;

  public EvidenceFile {
    Objects.requireNonNull(objectId, "objectId");
    recordedSha256 = recordedSha256 == null ? null : recordedSha256.clone();
    storedSha256 = storedSha256 == null ? null : storedSha256.clone();
  }

  @Override
  public byte[] recordedSha256() {
    return recordedSha256 == null ? null : recordedSha256.clone();
  }

  @Override
  public byte[] storedSha256() {
    return storedSha256 == null ? null : storedSha256.clone();
  }

  /** Nghi trùng mã băm với minh chứng của nhiệm vụ khác. */
  public boolean duplicateSuspect() {
    return duplicateOfVersionId != null;
  }

  /** Mã băm ghi nhận khớp đối tượng đang lưu ở SVC-08 (BR-07-02). */
  public boolean hashMatches() {
    return recordedSha256 != null
        && recordedSha256.length == 32
        && storedVersion != null
        && storedVersion == recordedVersion
        && Arrays.equals(recordedSha256, storedSha256);
  }

  /** Giải trình hợp lệ khi độ dài sau khi bỏ khoảng trắng hai đầu nằm trong 20..1000 ký tự. */
  public static boolean justificationValid(String text) {
    if (text == null) {
      return false;
    }
    int length = text.strip().length();
    return length >= JUSTIFICATION_MIN && length <= JUSTIFICATION_MAX;
  }
}
