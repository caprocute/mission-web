package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho đối tượng chưa cấu hình: mọi thao tác báo kho chưa sẵn sàng (CORE-06-08). */
class PackageFilesTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  @Test
  void khoChuaCauHinhThiMoiThaoTacBaoChuaSanSang() {
    PackageFiles none = PackageFiles.NONE;
    assertThatThrownBy(() -> none.open(TENANT, "k"))
        .isInstanceOf(PackageFiles.UnavailableException.class)
        .hasMessage("Kho lưu trữ hồ sơ chưa sẵn sàng.");
    assertThatThrownBy(() -> none.size(TENANT, "k"))
        .isInstanceOf(PackageFiles.UnavailableException.class);
    assertThatThrownBy(() -> none.create(TENANT, "k", "application/zip"))
        .isInstanceOf(PackageFiles.UnavailableException.class);
    assertThatThrownBy(() -> none.downloadUrl(TENANT, "k", "a.zip", Duration.ofMinutes(1)))
        .isInstanceOf(PackageFiles.UnavailableException.class);
  }
}
