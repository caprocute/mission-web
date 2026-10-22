package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;

/** Vai trò realm của người dùng trên Keycloak (SVC-02), đích đồng bộ của CORE-08-16. */
public interface RealmRoles {

  List<String> rolesOf(UUID userId);

  void add(UUID userId, String role);

  void remove(UUID userId, String role);
}
