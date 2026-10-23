package vn.viettel.okrkpi.integ.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Job xác minh định kỳ trên môi trường thử của đối tác (DV-13-11). Chỉ chạy khi có biến môi trường
 * {@code PACT_DOI_TAC_CAU_HINH} trỏ tới tệp JSON dạng {@code [{"provider", "url", "tokenFile",
 * "stateChangeUrl"}]}: {@code tokenFile} là tệp mã thông báo do OpenBao gắn vào pod của job, không
 * đặt giá trị trong cấu hình. Kết quả ghi ở {@code target/pact-doi-tac/ket-qua.json} (chỉ mô tả và
 * đường dẫn trường lệch, không có dữ liệu đối tác) để pipeline công bố lên Pact Broker khi QA-02
 * xong. Đối tác chưa có môi trường thử thì không khai trong cấu hình; hợp đồng của đối tác đó vẫn
 * được xác minh với bản ghi đã che ở {@link XacMinhPhiaDoiTacTest}.
 *
 * <pre>
 * PACT_DOI_TAC_CAU_HINH=/etc/vt-pact/doi-tac.json \
 *   mvn -pl services/integration-gateway -am verify -Dtest=XacMinhMoiTruongDoiTacTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "PACT_DOI_TAC_CAU_HINH", matches = ".+")
class XacMinhMoiTruongDoiTacTest {

  @Test
  void xacMinhHopDongTrenMoiTruongThuCuaDoiTac() throws IOException {
    JsonNode cfg = Pact.JSON.readTree(Path.of(System.getenv("PACT_DOI_TAC_CAU_HINH")).toFile());
    ArrayNode report = Pact.JSON.createArrayNode();
    List<String> failures = new ArrayList<>();
    for (JsonNode d : cfg) {
      String provider = d.path("provider").asText();
      Pact pact =
          PactFiles.names().stream()
              .map(Pact::load)
              .filter(p -> p.provider().equals(provider) && !p.interactions().isEmpty())
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Không có hợp đồng của " + provider));
      Optional<String> token =
          d.hasNonNull("tokenFile")
              ? Optional.of(
                  Files.readString(Path.of(d.get("tokenFile").asText()), StandardCharsets.UTF_8)
                      .strip())
              : Optional.empty();
      Optional<URI> state =
          d.hasNonNull("stateChangeUrl")
              ? Optional.of(URI.create(d.get("stateChangeUrl").asText()))
              : Optional.empty();
      for (PactVerifier.Result r :
          new PactVerifier(URI.create(d.path("url").asText()), token, state).verify(pact)) {
        ObjectNode row =
            report.addObject().put("provider", provider).put("description", r.description());
        row.put("ok", r.ok());
        r.errors().forEach(row.putArray("errors")::add);
        r.errors().forEach(e -> failures.add(provider + " / " + r.description() + ": " + e));
      }
    }
    Path out = Path.of("target/pact-doi-tac");
    Files.createDirectories(out);
    Pact.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve("ket-qua.json").toFile(), report);
    assertThat(failures).isEmpty();
  }
}
