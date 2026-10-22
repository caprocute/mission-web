package vn.viettel.okrkpi.org.application.scim;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.ScimStore.Resource;

/** Kiểu dữ liệu vào, ra của {@link ScimService}. */
public final class ScimModels {

  private ScimModels() {
  }

  /** Client SCIM đã xác thực: chủ thể (tài khoản dịch vụ, tenant trong token) và nguồn ({@code azp}). */
  public record Client(Actor actor, String source) {
  }

  /**
   * Thuộc tính người dùng SCIM được nhận. Số định danh, ngày sinh (phần mở rộng của nền tảng) là C2: chỉ dùng để khớp,
   * tạo người, không trả lại và không ghi nhật ký.
   */
  public record UserInput(String externalId, String userName, String displayName, String title, boolean active,
      String nationalId, LocalDate birthDate, Boolean partyMember) {
  }

  /** Người dùng SCIM trả ra: ánh xạ cùng trạng thái hiện hành của cán bộ. */
  public record UserView(Resource resource, String employmentStatus, boolean partyMember) {
  }

  /** Nhóm SCIM: {@code members} là mã tài nguyên người dùng SCIM của cùng nguồn. */
  public record GroupInput(String externalId, String displayName, List<UUID> members) {
    public GroupInput {
      members = members == null ? List.of() : List.copyOf(members);
    }
  }

  public record GroupView(Resource resource, List<UUID> members) {
  }

  /** Một trang của ListResponse (RFC 7644 mục 3.4.2); {@code startIndex} đếm từ 1. */
  public record ListPage<T>(int totalResults, int startIndex, List<T> resources) {
  }

  /** Kết quả lệnh tạo: {@code replayed} là lệnh lặp đã được bỏ qua. */
  public record Created<T>(T value, boolean replayed) {
  }
}
