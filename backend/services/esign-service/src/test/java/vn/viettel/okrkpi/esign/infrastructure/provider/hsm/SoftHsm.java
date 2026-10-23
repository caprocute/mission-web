package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token SoftHSM2 cho kiểm thử (DV-10-03). PKCS#11 là thư viện nạp vào tiến trình JVM nên không đặt
 * được trong container riêng: kiểm thử dùng gói {@code softhsm2} cài trên máy build và bị bỏ qua
 * khi máy không có. Biến {@code SOFTHSM2_CONF} do surefire/failsafe đặt (pom), tệp do lớp này ghi.
 */
final class SoftHsm {

  static final List<String> LIBRARIES =
      List.of(
          "/usr/lib/softhsm/libsofthsm2.so",
          "/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so",
          "/usr/local/lib/softhsm/libsofthsm2.so",
          "/opt/homebrew/lib/softhsm/libsofthsm2.so");

  private static final Pattern SLOT = Pattern.compile("reassigned to slot (\\d+)");

  private SoftHsm() {}

  static String library() {
    return LIBRARIES.stream().filter(p -> Files.isReadable(Path.of(p))).findFirst().orElse(null);
  }

  static boolean available() {
    return library() != null && System.getenv("SOFTHSM2_CONF") != null && onPath("softhsm2-util");
  }

  static boolean onPath(String tool) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (String dir : path.split(java.io.File.pathSeparator)) {
      if (Files.isExecutable(Path.of(dir, tool))) {
        return true;
      }
    }
    return false;
  }

  /** Một token mới với PIN ngẫu nhiên. */
  record Token(String label, int slot, String pin) {
    HsmProperties properties(boolean provision) {
      return new HsmProperties(
          true, library(), slot, null, pin, null, null, 2, Duration.ofSeconds(2), provision);
    }
  }

  static Token newToken() throws IOException, InterruptedException {
    Path conf = Path.of(System.getenv("SOFTHSM2_CONF"));
    Path tokens = conf.resolveSibling("tokens");
    Files.createDirectories(tokens);
    if (!Files.exists(conf)) {
      Files.writeString(
          conf,
          "directories.tokendir = " + tokens + "\nobjectstore.backend = file\nlog.level = ERROR\n",
          StandardCharsets.UTF_8);
    }
    SecureRandom random = new SecureRandom();
    byte[] b = new byte[8];
    random.nextBytes(b);
    String label = "t" + HexFormat.of().formatHex(b).substring(0, 10);
    random.nextBytes(b);
    String pin = "P" + HexFormat.of().formatHex(b);
    random.nextBytes(b);
    String soPin = "S" + HexFormat.of().formatHex(b);
    String out =
        run(
            "softhsm2-util",
            "--init-token",
            "--free",
            "--label",
            label,
            "--so-pin",
            soPin,
            "--pin",
            pin);
    Matcher m = SLOT.matcher(out);
    if (!m.find()) {
      throw new IllegalStateException("softhsm2-util không trả slot: " + out);
    }
    return new Token(label, Integer.parseInt(m.group(1)), pin);
  }

  static String run(String... command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IllegalStateException(command[0] + " lỗi: " + out);
    }
    return out;
  }
}
