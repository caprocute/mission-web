package vn.viettel.okrkpi.integ.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Quét dữ liệu cá nhân trong bản ghi tương tác và tệp hợp đồng của repo (DV-13-11): không có số
 * CCCD, số điện thoại, thư điện tử thật; header xác thực chỉ mang mã giả lập, không có cookie. Mẫu
 * quét dùng chung với công cụ che ({@link RecordingMasker}). Bước gitleaks của pipeline (cấu hình
 * {@code .gitleaks.toml}) quét thêm bí mật.
 */
class BanGhiKhongCoDuLieuCaNhanTest {

  static List<Path> files() throws IOException {
    List<Path> out = new ArrayList<>();
    for (Path d : List.of(Recordings.dir(), PactFiles.dir())) {
      try (Stream<Path> s = Files.walk(d)) {
        s.filter(Files::isRegularFile).forEach(out::add);
      }
    }
    return out;
  }

  static List<String> scan(String name, String text) {
    List<String> found = new ArrayList<>();
    for (Pattern p : List.of(RecordingMasker.CCCD, RecordingMasker.PHONE, RecordingMasker.EMAIL)) {
      if (p.matcher(text).find()) {
        found.add(
            name
                + ": khớp mẫu "
                + (p == RecordingMasker.CCCD
                    ? "CCCD"
                    : p == RecordingMasker.PHONE ? "số điện thoại" : "thư điện tử"));
      }
    }
    return found;
  }

  @Test
  void khongCoDuLieuCaNhanTrongBanGhiVaHopDong() throws IOException {
    List<Path> files = files();
    assertThat(files).hasSizeGreaterThanOrEqualTo(7);
    List<String> found = new ArrayList<>();
    for (Path f : files) {
      found.addAll(scan(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
    }
    assertThat(found).isEmpty();
  }

  @Test
  void headerKhongMangMaThatHayCookie() throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> s = Files.walk(Recordings.dir())) {
      for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
        JsonNode n = Pact.JSON.readTree(f.toFile());
        assertThat(n.path("daChe").asBoolean()).as("%s chưa che", f.getFileName()).isTrue();
        for (JsonNode x : n.path("exchanges")) {
          for (String side : List.of("request", "response")) {
            x.path(side)
                .path("headers")
                .fields()
                .forEachRemaining(
                    h -> {
                      String k = h.getKey().toLowerCase(java.util.Locale.ROOT);
                      if (k.contains("cookie")
                          || k.equals("x-api-key")
                          || (k.equals("authorization")
                              && !h.getValue().asText().equals("Bearer ma-gia-lap"))) {
                        found.add(f.getFileName() + ": header " + h.getKey());
                      }
                    });
          }
        }
      }
    }
    assertThat(found).isEmpty();
  }

  @Test
  void mauQuetBatDuocDuLieuThat() {
    assertThat(scan("x", "{\"cccd\":\"079085001234\"}")).hasSize(1);
    assertThat(scan("x", "gọi 0912345678")).hasSize(1);
    assertThat(scan("x", "gọi +84983123456")).hasSize(1);
    assertThat(scan("x", "thư a.b@donvi.gov.vn")).hasSize(1);
    assertThat(scan("x", "000123456789 0000123456 nguoi-1a2b@example.invalid 2026-10-20T01:00:00Z"))
        .isEmpty();
  }
}
