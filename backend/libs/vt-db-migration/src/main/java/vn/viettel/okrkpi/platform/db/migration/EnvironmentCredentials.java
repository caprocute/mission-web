package vn.viettel.okrkpi.platform.db.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lấy mật khẩu {@code mig_<schema>} theo thứ tự: biến môi trường {@code MIG_<SCHEMA>_PASSWORD}, rồi
 * tệp {@code <MIG_SECRETS_DIR>/mig_<schema>} (Secret do External Secrets Operator đồng bộ từ
 * OpenBao gắn vào Job di trú). Tên đăng nhập luôn là {@code mig_<schema>}.
 */
public final class EnvironmentCredentials implements MigrationCredentials {

  /** Biến môi trường trỏ tới thư mục chứa tệp mật khẩu (tuỳ chọn). */
  public static final String SECRETS_DIR_ENV = "MIG_SECRETS_DIR";

  private final Map<String, String> env;

  public EnvironmentCredentials() {
    this(System.getenv());
  }

  EnvironmentCredentials(Map<String, String> env) {
    this.env = Map.copyOf(env);
  }

  @Override
  public Credentials forSchema(String jdbcUrl, String schema) {
    String user = SchemaNames.migrationRole(schema);
    String password = env.get(SchemaNames.passwordEnvVar(schema));
    if (password == null) {
      String dir = env.get(SECRETS_DIR_ENV);
      if (dir != null) {
        Path file = Path.of(dir).resolve(user);
        if (Files.isRegularFile(file)) {
          try {
            password = Files.readString(file, StandardCharsets.UTF_8).strip();
          } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được tệp mật khẩu của " + user, e);
          }
        }
      }
    }
    if (password == null) {
      throw new IllegalStateException(
          "Thiếu mật khẩu của "
              + user
              + ": đặt biến "
              + SchemaNames.passwordEnvVar(schema)
              + " hoặc tệp trong "
              + SECRETS_DIR_ENV);
    }
    return new Credentials(user, password);
  }
}
