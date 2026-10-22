package vn.viettel.okrkpi.iam.events;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * DV-02-03 · Bước cuối của luồng {@code mission-browser}: chỉ chạy khi các bước trước, kể cả MFA khi bị yêu cầu, đã
 * thành công, rồi ghi thiết bị và dải mạng của lần đăng nhập này vào danh sách đã biết của người dùng (tối đa
 * {@value MissionEventListenerProvider#MAX_DEVICES} giá trị gần nhất mỗi loại). Nhờ vậy thiết bị chỉ qua được mật
 * khẩu không bao giờ được ghi nhận.
 */
final class MissionDeviceRememberAuthenticator implements Authenticator {

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    AuthenticationSessionModel auth = context.getAuthenticationSession();
    UserModel user = context.getUser();
    String devices = auth.getAuthNote(MissionMfaPolicyAuthenticator.NOTE_DEVICES);
    if (devices != null) {
      remember(user, MissionEventListenerProvider.DEVICES_ATTRIBUTE, List.of(devices.split(",")));
    }
    String network = auth.getAuthNote(MissionMfaPolicyAuthenticator.NOTE_NETWORK);
    if (network != null) {
      remember(user, MissionMfaPolicyAuthenticator.NETWORKS_ATTRIBUTE, List.of(network));
    }
    context.success();
  }

  static void remember(UserModel user, String attribute, List<String> values) {
    List<String> known = user.getAttributeStream(attribute).collect(Collectors.toCollection(ArrayList::new));
    boolean changed = false;
    for (String v : values) {
      if (!v.isBlank() && !known.contains(v)) {
        known.add(v);
        changed = true;
      }
    }
    if (!changed) {
      return;
    }
    while (known.size() > MissionEventListenerProvider.MAX_DEVICES) {
      known.remove(0);
    }
    user.setAttribute(attribute, known);
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    context.success();
  }

  @Override
  public boolean requiresUser() {
    return true;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
  }

  @Override
  public void close() {
  }
}
