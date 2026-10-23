package vn.viettel.okrkpi.org.domain.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Quy tắc phân loại lệch của bộ so khớp đồng bộ nhân sự (TL-02a QT-01 bước 7, 8; tình huống "hai hồ sơ trùng tên khác
 * ngày sinh"). Hàm thuần: nhận trạng thái nguồn và trạng thái đang dùng, trả danh sách phát hiện.
 * <ul>
 *   <li>Cao: không khớp người, đơn vị chính khác, chức vụ chính khác khi một phía là lãnh đạo (người đứng đầu),
 *       kiêm nhiệm lãnh đạo có ở một phía.
 *   <li>Vừa: cùng đơn vị và chức vụ nhưng ngày hiệu lực khác, trạng thái công tác khác (nghỉ hưu, thôi việc chưa cập
 *       nhật), chức vụ không lãnh đạo khác, đảng tịch khác (suy luận: đảng tịch đổi luồng đánh giá nhưng không đổi
 *       người đánh giá).
 *   <li>Thấp: kiêm nhiệm không lãnh đạo có ở một phía.
 * </ul>
 */
public final class DiscrepancyRules {

  /** Trạng thái công tác kết thúc phân công. */
  static final Set<String> TERMINAL_STATUSES = Set.of("NGHI_HUU", "THOI_VIEC");

  private DiscrepancyRules() {
  }

  /** Một phân công ở nguồn hoặc đang dùng (nhánh chính quyền hoặc đảng). */
  public record Post(UUID assignmentId, UUID orgUnitId, UUID jobTitleId, String kind, boolean head,
      LocalDate validFrom) {

    public boolean primary() {
      return "CHINH".equals(kind);
    }

    boolean samePost(Post o) {
      return Objects.equals(orgUnitId, o.orgUnitId) && Objects.equals(jobTitleId, o.jobTitleId);
    }
  }

  /** Thuộc tính so sánh được của một người. */
  public record PersonState(String employmentStatus, boolean partyMember, List<Post> posts) {

    public PersonState {
      posts = posts == null ? List.of() : List.copyOf(posts);
    }

    Post primary() {
      return posts.stream().filter(Post::primary).findFirst().orElse(null);
    }
  }

  /**
   * Một mục lệch phát hiện được.
   *
   * @param source giá trị phía nguồn (để ghi mã hoá và để áp dụng khi lấy theo nguồn)
   * @param current giá trị đang dùng, null khi phía nội bộ không có
   */
  public record Finding(DiscrepancyField field, Severity severity, SuggestedAction action, Object source,
      Object current, LocalDate effectiveFrom) {
  }

  /** Không khớp người (MSG-01-15): luôn mức Cao, đối soát thủ công. */
  public static Finding personMismatch(LocalDate effectiveFrom) {
    return new Finding(DiscrepancyField.PERSON_MATCH, Severity.CAO, SuggestedAction.DOI_SOAT_THU_CONG, null, null,
        effectiveFrom);
  }

  /** So trạng thái nguồn với trạng thái đang dùng của cùng một người. */
  public static List<Finding> compare(PersonState source, PersonState current, LocalDate today) {
    List<Finding> out = new ArrayList<>();
    // Nguồn không quản lý trạng thái công tác (CSDL đảng viên, DV-13-07) gửi rỗng: không so.
    if (source.employmentStatus() != null && !Objects.equals(source.employmentStatus(), current.employmentStatus())) {
      boolean terminal = TERMINAL_STATUSES.contains(source.employmentStatus());
      out.add(new Finding(DiscrepancyField.EMPLOYMENT_STATUS, Severity.VUA,
          terminal ? SuggestedAction.KET_THUC_PHAN_CONG : SuggestedAction.LAY_THEO_NGUON,
          source.employmentStatus(), current.employmentStatus(), null));
    }
    if (source.partyMember() != current.partyMember()) {
      out.add(new Finding(DiscrepancyField.PARTY_MEMBERSHIP, Severity.VUA, SuggestedAction.LAY_THEO_NGUON,
          source.partyMember(), current.partyMember(), null));
    }
    Post sp = source.primary();
    Post cp = current.primary();
    if (sp != null) {
      SuggestedAction take = sp.validFrom() != null && sp.validFrom().isAfter(today)
          ? SuggestedAction.TAO_DIEU_DONG_TUONG_LAI : SuggestedAction.LAY_THEO_NGUON;
      if (cp == null || !Objects.equals(sp.orgUnitId(), cp.orgUnitId())) {
        out.add(new Finding(DiscrepancyField.ORG_UNIT, Severity.CAO, take, sp, cp, sp.validFrom()));
      } else if (!Objects.equals(sp.jobTitleId(), cp.jobTitleId()) || sp.head() != cp.head()) {
        Severity s = sp.head() || cp.head() ? Severity.CAO : Severity.VUA;
        out.add(new Finding(DiscrepancyField.JOB_TITLE, s, take, sp, cp, sp.validFrom()));
      } else if (sp.validFrom() != null && !sp.validFrom().equals(cp.validFrom())) {
        out.add(new Finding(DiscrepancyField.ORG_UNIT, Severity.VUA, SuggestedAction.LAY_THEO_NGUON, sp, cp,
            sp.validFrom()));
      }
    }
    for (Post s : source.posts()) {
      if (!s.primary() && current.posts().stream().filter(c -> !c.primary()).noneMatch(s::samePost)) {
        out.add(new Finding(DiscrepancyField.CONCURRENT_ASSIGNMENT, s.head() ? Severity.CAO : Severity.THAP,
            SuggestedAction.LAY_THEO_NGUON, s, null, s.validFrom()));
      }
    }
    for (Post c : current.posts()) {
      if (!c.primary() && source.posts().stream().filter(s -> !s.primary()).noneMatch(c::samePost)) {
        out.add(new Finding(DiscrepancyField.CONCURRENT_ASSIGNMENT, c.head() ? Severity.CAO : Severity.THAP,
            SuggestedAction.KET_THUC_PHAN_CONG, null, c, null));
      }
    }
    return out;
  }
}
