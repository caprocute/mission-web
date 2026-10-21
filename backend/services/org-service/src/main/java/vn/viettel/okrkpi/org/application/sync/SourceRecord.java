package vn.viettel.okrkpi.org.application.sync;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Một bản ghi nhân sự đã chuẩn hoá do integration-gateway chuyển sang (DV-13-06..08). Số định danh và ngày sinh là
 * C2: chỉ sống trong bộ nhớ của lần xử lý, được ghi xuống dưới dạng chỉ mục mù và bản mã.
 *
 * @param externalId mã bản ghi ở nguồn
 * @param mappedPersonId người đã ánh xạ thủ công ({@code integ.mapping_xref}), null nếu chưa có
 * @param posts phân công ở nguồn; đơn vị và chức danh đã được gateway quy đổi sang mã nội bộ
 * @param effectiveFrom ngày hiệu lực theo quyết định nguồn (nếu có)
 */
public record SourceRecord(String externalId, UUID mappedPersonId, String fullName, String nationalId,
    LocalDate birthDate, String employmentStatus, boolean partyMember, List<SourcePost> posts,
    LocalDate effectiveFrom) {

  public SourceRecord {
    posts = posts == null ? List.of() : List.copyOf(posts);
  }

  /** Phân công ở nguồn. */
  public record SourcePost(UUID orgUnitId, UUID jobTitleId, String kind, boolean head, LocalDate validFrom) {
  }
}
