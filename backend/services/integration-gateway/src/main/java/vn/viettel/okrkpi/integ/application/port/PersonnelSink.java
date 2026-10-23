package vn.viettel.okrkpi.integ.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Chuyển lô nhân sự đã chuẩn hoá từ HRM cho org-service (DV-13-08, cùng cổng nội bộ lô đồng bộ của
 * DV-04-07): org-service khớp người, ghi {@code org.person}, {@code org.assignment} và phát EVT-15,
 * EVT-16. Đơn vị, chức danh, người đã quy đổi sang mã nội bộ qua {@code mapping_xref}.
 */
public interface PersonnelSink {

  /** Phân công ở nguồn. */
  record Post(UUID orgUnitId, UUID jobTitleId, String kind, boolean head, LocalDate validFrom) {}

  /** Một người; số định danh và ngày sinh là C2, không in ra nhật ký. */
  record Person(
      String externalId,
      UUID mappedPersonId,
      String fullName,
      String nationalId,
      LocalDate birthDate,
      String employmentStatus,
      boolean partyMember,
      List<Post> posts,
      LocalDate effectiveFrom) {

    public Person {
      posts = posts == null ? List.of() : List.copyOf(posts);
    }

    @Override
    public String toString() {
      return "Person[" + externalId + ", " + posts.size() + " phân công]";
    }
  }

  /**
   * Gửi một lô của lần chạy {@code runId}; {@code last} đóng lần chạy (một trang HRM hay một tệp lô
   * đến ngoài lần chạy là một lần chạy trọn). Lỗi kết nối ném ngoại lệ để luồng vào thử lại rồi
   * chuyển thư chết.
   */
  void forward(
      TenantId tenant, String connectorCode, UUID runId, List<Person> persons, boolean last);

  /**
   * Đóng lần chạy kéo hết trang (DV-13-06) kèm số thư chết và lý do lỗi (nếu có), không kèm bản
   * ghi.
   */
  void close(TenantId tenant, String connectorCode, UUID runId, int deadLetters, String reason);
}
