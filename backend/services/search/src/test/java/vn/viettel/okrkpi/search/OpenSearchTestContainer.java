package vn.viettel.okrkpi.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * OpenSearch 2.19 có plugin {@code analysis-icu} (ảnh chính thức không kèm). Tệp zip plugin tải một
 * lần về {@code target/} rồi cài ngoại tuyến khi dựng ảnh, nên bước dựng ảnh không cần mạng.
 */
final class OpenSearchTestContainer {

  static final String VERSION = "2.19.3";
  private static final String PLUGIN_URL =
      "https://artifacts.opensearch.org/releases/plugins/analysis-icu/"
          + VERSION
          + "/analysis-icu-"
          + VERSION
          + ".zip";

  private OpenSearchTestContainer() {}

  static GenericContainer<?> create() {
    Path zip = plugin();
    ImageFromDockerfile image =
        new ImageFromDockerfile("mission-test/opensearch-icu:" + VERSION, false)
            .withFileFromPath("analysis-icu.zip", zip)
            .withDockerfileFromBuilder(
                b ->
                    b.from("mirror.gcr.io/opensearchproject/opensearch:" + VERSION)
                        .copy("analysis-icu.zip", "/tmp/analysis-icu.zip")
                        .run(
                            "/usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/analysis-icu.zip")
                        .build());
    return new GenericContainer<>(image)
        .withEnv("discovery.type", "single-node")
        .withEnv("DISABLE_SECURITY_PLUGIN", "true")
        .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
        .withExposedPorts(9200)
        .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(3));
  }

  private static Path plugin() {
    Path zip = Path.of("target", "opensearch", "analysis-icu-" + VERSION + ".zip");
    if (Files.exists(zip)) {
      return zip;
    }
    try {
      Files.createDirectories(zip.getParent());
      HttpResponse<Path> r =
          HttpClient.newBuilder()
              .proxy(java.net.ProxySelector.getDefault())
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(PLUGIN_URL)).build(),
                  HttpResponse.BodyHandlers.ofFile(zip));
      if (r.statusCode() != 200) {
        Files.deleteIfExists(zip);
        throw new IllegalStateException("Không tải được plugin analysis-icu: " + r.statusCode());
      }
      return zip;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
