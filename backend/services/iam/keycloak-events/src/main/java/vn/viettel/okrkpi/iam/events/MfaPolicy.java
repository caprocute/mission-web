package vn.viettel.okrkpi.iam.events;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * DV-02-03 · QT-00 bước 4 · Quyết định có bắt buộc bậc 2 (MFA) khi đăng nhập hay không, theo ba nguồn của TL-02a:
 * vai trò yêu cầu AAL2 (BR-00-01, danh sách TL-01), tenant bật MFA cho mọi người dùng (TL-04, hệ thống cấp độ 4), và
 * điểm rủi ro (BR-00-06). Điểm rủi ro là tổng trọng số của các dấu hiệu, công thức đơn giản vì TL không đặc tả
 * (suy luận, ghi ở wiki trang 14):
 * <pre>
 *   điểm = wThiếtBịMới·[thiết bị mới] + wViTríMới·[dải mạng mới] + wSai·[có lần đăng nhập sai chưa xoá]
 *   bắt buộc MFA khi điểm ≥ ngưỡng
 * </pre>
 * Thiết bị và dải mạng chỉ coi là mới khi người dùng đã có ít nhất một giá trị đã biết; lần đăng nhập đầu tiên chỉ ghi
 * nhận, giống quy ước của thông báo MSG-00-08 ở DV-02-05.
 */
final class MfaPolicy {

  /** Lý do bắt buộc MFA, ghi vào chi tiết sự kiện đăng nhập để kiểm toán (không chứa dữ liệu cá nhân). */
  static final String VAI_TRO = "VAI_TRO";
  static final String TENANT = "TENANT";
  static final String THIET_BI_MOI = "THIET_BI_MOI";
  static final String VI_TRI_MOI = "VI_TRI_MOI";
  static final String RUI_RO = "RUI_RO";

  /** Vai trò bắt buộc bậc 2 ngay khi đăng nhập (BR-00-01 và danh sách vai trò của TL-01 SVC-02). */
  static final List<String> DEFAULT_ROLES = List.of("platform_admin", "tenant_admin", "appraiser", "decider",
      "second_approver", "inspector");

  record Settings(List<String> roles, int weightNewDevice, int weightNewNetwork, int weightFailures, int threshold) {

    static Settings defaults() {
      return new Settings(DEFAULT_ROLES, 50, 50, 25, 50);
    }
  }

  record Input(Set<String> mfaRoles, boolean tenantRequiresMfa, Collection<String> knownDevices,
      Collection<String> deviceKeys, Collection<String> knownNetworks, String network, int recentFailures) {
  }

  record Decision(boolean required, boolean newDevice, boolean newNetwork, int score, List<String> reasons) {
  }

  private MfaPolicy() {
  }

  static Decision evaluate(Settings s, Input in) {
    boolean newDevice = !in.knownDevices().isEmpty() && in.deviceKeys().stream().noneMatch(in.knownDevices()::contains);
    boolean newNetwork = in.network() != null && !in.knownNetworks().isEmpty()
        && !in.knownNetworks().contains(in.network());
    int score = (newDevice ? s.weightNewDevice() : 0) + (newNetwork ? s.weightNewNetwork() : 0)
        + (in.recentFailures() > 0 ? s.weightFailures() : 0);
    List<String> reasons = new ArrayList<>();
    if (!in.mfaRoles().isEmpty()) {
      reasons.add(VAI_TRO);
    }
    if (in.tenantRequiresMfa()) {
      reasons.add(TENANT);
    }
    if (score >= s.threshold()) {
      if (newDevice) {
        reasons.add(THIET_BI_MOI);
      }
      if (newNetwork) {
        reasons.add(VI_TRI_MOI);
      }
      if (!newDevice && !newNetwork) {
        reasons.add(RUI_RO);
      }
    }
    return new Decision(!reasons.isEmpty(), newDevice, newNetwork, score, List.copyOf(reasons));
  }

  /** Dải mạng của địa chỉ: IPv4 /24, IPv6 /48; địa chỉ không đọc được trả {@code null} (không xét vị trí). */
  static String network(String address) {
    if (address == null || address.isBlank() || !address.matches("[0-9A-Fa-f:.]+")) {
      return null;
    }
    try {
      byte[] b = InetAddress.getByName(address).getAddress();
      if (b.length == 4) {
        return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0/24";
      }
      return String.format("%02x%02x:%02x%02x:%02x%02x::/48", b[0], b[1], b[2], b[3], b[4], b[5]);
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
