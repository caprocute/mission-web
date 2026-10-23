package vn.viettel.okrkpi.integ.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tệp hợp đồng đúng khuôn để công bố được lên Pact Broker khi QA-02 dựng xong (DV-13-11): đặc tả
 * 3.0.0, bên tiêu dùng là integration-gateway, mỗi tương tác có mô tả riêng và trạng thái đối tác,
 * luật so khớp là đường dẫn {@code $...}; mỗi hệ ngoài có bản ghi tương tác đã che để kiểm ngược.
 */
class PactFileTest {

  static List<String> files() {
    return PactFiles.names();
  }

  @Test
  void coHopDongVOfficeVaCacBoKetNoiKhac() {
    assertThat(files())
        .contains(
            "integration-gateway-voffice.json",
            "integration-gateway-voffice-tin-nhan.json",
            "integration-gateway-hrm.json",
            "integration-gateway-lgsp.json");
  }

  @ParameterizedTest
  @MethodSource("files")
  void dungKhuonPactV3(String file) {
    Pact p = Pact.load(file);
    assertThat(p.consumer()).isEqualTo(Pact.CONSUMER);
    assertThat(file).isEqualTo("integration-gateway-" + file.substring(20));
    assertThat(p.provider()).matches("[a-z0-9-]+");
    assertThat(p.raw().at("/metadata/pactSpecification/version").asText()).isEqualTo(Pact.SPEC);
    assertThat(p.interactions().isEmpty())
        .as("một tệp chỉ chứa tương tác HTTP hoặc chỉ chứa tin nhắn")
        .isNotEqualTo(p.messages().isEmpty());
    Set<String> seen = new HashSet<>();
    for (Pact.Interaction i : p.interactions()) {
      assertThat(seen.add(i.description())).as("mô tả trùng: %s", i.description()).isTrue();
      assertThat(i.states()).as("thiếu trạng thái đối tác: %s", i.description()).isNotEmpty();
      assertThat(i.path()).startsWith("/");
      i.responseRules("body").fieldNames().forEachRemaining(k -> assertThat(k).startsWith("$"));
    }
    for (Pact.Message m : p.messages()) {
      assertThat(seen.add(m.description())).isTrue();
      assertThat(m.states()).isNotEmpty();
      assertThat(m.contents().isObject()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("files")
  void hopDongHttpCoBanGhiDaChe(String file) {
    Pact p = Pact.load(file);
    if (!p.interactions().isEmpty()) {
      assertThat(Files.isDirectory(Recordings.dir().resolve(p.provider())))
          .as("chưa có bản ghi tương tác đã che của %s", p.provider())
          .isTrue();
    }
  }
}
