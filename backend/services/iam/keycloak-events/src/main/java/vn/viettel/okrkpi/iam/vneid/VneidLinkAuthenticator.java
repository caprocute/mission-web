package vn.viettel.okrkpi.iam.vneid;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator;
import org.keycloak.authentication.authenticators.broker.util.SerializedBrokeredIdentityContext;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Bước {@value VneidLinkAuthenticatorFactory#ID} của hai luồng sau môi giới VNeID (DV-02-04):
 *
 * <ul>
 *   <li>Luồng đăng nhập lần đầu: gắn danh tính VNeID vào đúng người mà {@link VneidIdentityProvider} đã khớp qua
 *       org-service (người đã có tài khoản do đồng bộ nhân sự tạo). Không có người đã khớp thì dừng; luồng không có bước
 *       tạo tài khoản nên không bao giờ tạo người mới.
 *   <li>Luồng sau đăng nhập: người đã gắn từ trước.
 * </ul>
 *
 * Cả hai đặt bậc xác thực 2 (VNeID mức 2 được coi là đã đạt MFA, TL-04 SEC-13..17), nên token mang {@code acr=aal2}.
 */
public class VneidLinkAuthenticator implements Authenticator {

  private static final Logger LOG = Logger.getLogger(VneidLinkAuthenticator.class);

  static final int LEVEL = 2;

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    AuthenticationSessionModel authSession = context.getAuthenticationSession();
    UserModel user = context.getUser();
    SerializedBrokeredIdentityContext first = SerializedBrokeredIdentityContext.readFromAuthenticationSession(
        authSession, AbstractIdpAuthenticator.BROKERED_CONTEXT_NOTE);
    if (first != null) {
      SerializedBrokeredIdentityContext.ContextDataEntry entry = first.getContextData()
          .get(VneidIdentityProvider.PERSON_NOTE);
      String personId = entry == null ? null : unquote(entry.getData());
      user = personId == null ? null : context.getSession().users().getUserById(context.getRealm(), personId);
      if (user == null) {
        LOG.warn("Luồng đăng nhập lần đầu VNeID không có người đã khớp; không tạo tài khoản");
        context.failureChallenge(AuthenticationFlowError.IDENTITY_PROVIDER_ERROR,
            context.form().setError(VneidIdentityProvider.MSG_NOT_LINKED)
                .createErrorPage(Response.Status.UNAUTHORIZED));
        return;
      }
      context.setUser(user);
    }
    if (user == null) {
      context.failureChallenge(AuthenticationFlowError.UNKNOWN_USER,
          context.form().setError(VneidIdentityProvider.MSG_NOT_LINKED).createErrorPage(Response.Status.UNAUTHORIZED));
      return;
    }
    new AcrStore(context.getSession(), authSession).setLevelAuthenticated(LEVEL);
    context.success();
  }

  /** Giá trị ngữ cảnh được tuần tự hoá dạng chuỗi JSON. */
  static String unquote(String data) {
    if (data == null) {
      return null;
    }
    String s = data.trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1);
    }
    return s.isEmpty() ? null : s;
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    authenticate(context);
  }

  @Override
  public boolean requiresUser() {
    return false;
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
