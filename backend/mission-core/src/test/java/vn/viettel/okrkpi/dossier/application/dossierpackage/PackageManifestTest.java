package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;

/** Manifest JCS của gói (CORE-06-07): khoá xếp, mục xếp theo mã biểu mẫu, băm ổn định. */
class PackageManifestTest {

  static final UUID A = UUID.fromString("0f000000-0000-7000-8000-000000000001");
  static final UUID B = UUID.fromString("f0000000-0000-7000-8000-000000000002");
  static final UUID PROFILE = UUID.fromString("01927a06-0000-7000-8000-000000000003");

  @Test
  void chuoiChuanTacKhongPhuThuocThuTuVaBamLaSha256CuaChuoi() throws Exception {
    byte[] sa = new byte[32];
    byte[] sb = new byte[32];
    sb[0] = 1;
    PackageManifest.Built one =
        PackageManifest.of(List.of(form(B, "MAU_3", sb), form(A, "BANG_CHAM_DIEM", sa)));
    PackageManifest.Built two =
        PackageManifest.of(List.of(form(A, "BANG_CHAM_DIEM", sa), form(B, "MAU_3", sb)));
    assertThat(one.canonicalJson()).isEqualTo(two.canonicalJson());
    assertThat(one.canonicalJson())
        .isEqualTo(
            "[{\"bieuMauId\":\""
                + A
                + "\",\"hoSoId\":\""
                + PROFILE
                + "\",\"khoaDoiTuong\":\"k/"
                + A
                + "\",\"maMau\":\"BANG_CHAM_DIEM\",\"sha256\":\""
                + HexFormat.of().formatHex(sa)
                + "\"},{\"bieuMauId\":\""
                + B
                + "\",\"hoSoId\":\""
                + PROFILE
                + "\",\"khoaDoiTuong\":\"k/"
                + B
                + "\",\"maMau\":\"MAU_3\",\"sha256\":\""
                + HexFormat.of().formatHex(sb)
                + "\"}]");
    assertThat(one.sha256())
        .isEqualTo(
            MessageDigest.getInstance("SHA-256")
                .digest(one.canonicalJson().getBytes(StandardCharsets.UTF_8)));
    assertThat(PackageManifest.hashes(one.canonicalJson()))
        .containsEntry(A, HexFormat.of().formatHex(sa))
        .containsEntry(B, HexFormat.of().formatHex(sb));
  }

  @Test
  void manifestRongHoacHongThiBangBamRong() {
    assertThat(PackageManifest.hashes(null)).isEmpty();
    assertThat(PackageManifest.hashes("[]")).isEmpty();
    assertThat(PackageManifest.hashes("{hong")).isEmpty();
  }

  private static FormRow form(UUID id, String code, byte[] sha) {
    return new FormRow(
        id,
        UUID.randomUUID(),
        20260,
        UUID.randomUUID(),
        PROFILE,
        code,
        "1",
        new byte[32],
        null,
        "k/" + id,
        sha,
        FormStatus.DA_SINH,
        null);
  }
}
