package vn.viettel.okrkpi.iam.events;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Đăng ký tài nguyên REST {@value #ID} của realm (DV-02-08): {@code /realms/{realm}/mission-iam/...}. */
public class MissionIamResourceProviderFactory implements RealmResourceProviderFactory {

  public static final String ID = "mission-iam";

  @Override
  public RealmResourceProvider create(KeycloakSession session) {
    return new RealmResourceProvider() {
      @Override
      public Object getResource() {
        return new MissionIamResource(session);
      }

      @Override
      public void close() {
      }
    };
  }

  @Override
  public void init(Config.Scope config) {
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
  }

  @Override
  public void close() {
  }

  @Override
  public String getId() {
    return ID;
  }
}
