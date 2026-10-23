package vn.viettel.okrkpi.shared.crypto.bcy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.viettel.okrkpi.shared.crypto.CryptoAutoConfiguration;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;

/**
 * Chọn nhà cung cấp theo {@code okrkpi.crypto.provider} và từ chối khởi động khi thiếu (DV-10-05).
 */
class BcyCryptoConfigurationTest {

  private static final String LIB = GiaLapBcyCryptoLibrary.class.getName();
  private static final String ALLOWED =
      "okrkpi.crypto.bcy.allowed-algorithms="
          + String.join(",", GiaLapBcyCryptoLibrary.ALGORITHMS.values());

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration.class));

  private ApplicationContextRunner bcy(String... extra) {
    return runner
        .withPropertyValues(
            "okrkpi.crypto.provider=bcy",
            "okrkpi.crypto.bcy.library-class=" + LIB,
            "okrkpi.crypto.bcy.params.slot=0",
            ALLOWED)
        .withPropertyValues(extra);
  }

  @Test
  void standardIsDefault() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(CryptoProvider.class);
          assertThat(ctx.getBean(CryptoProvider.class)).isInstanceOf(JcaAesGcmCryptoProvider.class);
          assertThat(ctx).doesNotHaveBean(BcyCryptoProvider.class);
        });
    runner
        .withPropertyValues("okrkpi.crypto.provider=standard")
        .run(
            ctx ->
                assertThat(ctx.getBean(CryptoProvider.class))
                    .isInstanceOf(JcaAesGcmCryptoProvider.class));
  }

  @Test
  void bcyReplacesStandardProviderEverywhere() {
    bcy()
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(CryptoProvider.class);
              assertThat(ctx.getBean(CryptoProvider.class)).isInstanceOf(BcyCryptoProvider.class);
              assertThat(ctx).doesNotHaveBean(JcaAesGcmCryptoProvider.class);
              assertThat(ctx).hasSingleBean(FieldEncryptor.class);
            });
  }

  @Test
  void missingLibraryClassStopsStartupWithVietnameseReason() {
    bcy("okrkpi.crypto.bcy.library-class=vn.bcy.KhongCo")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .hasMessageContaining("không nạp được lớp thư viện Ban Cơ yếu vn.bcy.KhongCo"));
    runner
        .withPropertyValues("okrkpi.crypto.provider=bcy", ALLOWED)
        .run(ctx -> assertThat(ctx).getFailure().hasMessageContaining("chưa khai lớp thư viện"));
  }

  @Test
  void missingLibraryFileStopsStartup(@TempDir Path dir) {
    Path jar = dir.resolve("bcy-crypto.jar");
    bcy("okrkpi.crypto.bcy.library-path=" + jar)
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .hasMessageContaining("không tìm thấy thư viện Ban Cơ yếu tại " + jar));
  }

  @Test
  void missingDeviceStopsStartup(@TempDir Path dir) {
    bcy("okrkpi.crypto.bcy.params.device-file=" + dir.resolve("token0"))
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .hasMessageContaining("không mở được thiết bị mật mã Ban Cơ yếu"));
    runner
        .withPropertyValues(
            "okrkpi.crypto.provider=bcy", "okrkpi.crypto.bcy.library-class=" + LIB, ALLOWED)
        .run(ctx -> assertThat(ctx).getFailure().hasMessageContaining("không có khe khoá"));
  }

  @Test
  void algorithmOutsideAllowListStopsStartup() {
    bcy("okrkpi.crypto.bcy.allowed-algorithms=GIA_LAP_AEAD,GIA_LAP_MAC")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .hasMessageContaining("không thuộc danh sách được phép")
                    .hasMessageContaining("GIA_LAP_KY"));
    runner
        .withPropertyValues(
            "okrkpi.crypto.provider=bcy",
            "okrkpi.crypto.bcy.library-class=" + LIB,
            "okrkpi.crypto.bcy.params.slot=0")
        .run(ctx -> assertThat(ctx).getFailure().hasMessageContaining("thuật toán được phép"));
  }

  @Test
  void simulatedLibraryIsRefusedInProduction() {
    bcy("spring.profiles.active=prod")
        .run(
            ctx -> assertThat(ctx).getFailure().hasMessageContaining("giả lập ở hồ sơ production"));
  }

  @Test
  void libraryNotImplementingInterfaceIsRefused() {
    bcy("okrkpi.crypto.bcy.library-class=java.lang.String")
        .run(ctx -> assertThat(ctx).getFailure().hasMessageContaining("không hiện thực"));
  }

  @Test
  void requiredRolesMustBeDeclared() {
    java.util.Map<BcyCryptoLibrary.Role, String> partial =
        java.util.Map.of(BcyCryptoLibrary.Role.AEAD, "A");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BcyLibraryLoader.checkAlgorithms(partial, java.util.Set.of("A")))
        .isInstanceOf(BcyUnavailableException.class)
        .hasMessageContaining("không khai thuật toán cho vai trò");
  }
}
