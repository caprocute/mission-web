package vn.viettel.okrkpi.iam.events;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Optional;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;

/**
 * DV-02-08 · Bước "Mã khôi phục" trong nhánh bậc 2 của {@code mission-browser}, hiện ở "Phương thức khác" khi người dùng
 * có mã còn hạn. Nhập đúng: xoá mã (dùng một lần), gỡ SmartOTP cũ vì thiết bị đã mất, đặt required action cài lại
 * SmartOTP; token chỉ được phát sau khi cài xong và nhập đúng một mã SmartOTP mới, nên mã khôi phục một mình không thoả
 * bậc 2 (BR-00-01). Nhập sai tính vào bộ đếm khoá tài khoản của realm (5 lần, BR-00-05).
 */
final class RecoveryCodeAuthenticator implements Authenticator {

  static final String FIELD = "recoveryCode";
  static final String TEMPLATE = "mission-recovery-code.ftl";

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    context.challenge(context.form().createForm(TEMPLATE));
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    UserModel user = context.getUser();
    String input = context.getHttpRequest().getDecodedFormParameters().getFirst(FIELD);
    Optional<CredentialModel> code = RecoveryCodeStore.active(user, Instant.now());
    if (code.isEmpty() || !RecoveryCodeStore.matches(code.get(), input)) {
      RecoveryEvents.used(context.getSession(), user, false);
      context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
      Response challenge = context.form().setError("Mã khôi phục không đúng hoặc đã hết hạn.")
          .createForm(TEMPLATE);
      context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
      return;
    }
    RecoveryCodeStore.revokeAll(user);
    user.credentialManager().getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE).toList()
        .forEach(c -> user.credentialManager().removeStoredCredentialById(c.getId()));
    user.addRequiredAction(UserModel.RequiredAction.CONFIGURE_TOTP);
    RecoveryEvents.used(context.getSession(), user, true);
    context.getEvent().detail("mission_recovery_code", "used");
    context.success();
  }

  @Override
  public boolean requiresUser() {
    return true;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return RecoveryCodeStore.active(user, Instant.now()).isPresent();
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
  }

  @Override
  public void close() {
  }
}
