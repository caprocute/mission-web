package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.ComponentRegistryService;
import vn.viettel.okrkpi.config.application.LayoutValidator;
import vn.viettel.okrkpi.config.application.port.PluginWidgets;

/**
 * CORE-08-09: component registry có phiên bản và bộ kiểm bố cục SDUI gắn vào vòng duyệt UI Pack.
 * Manifest nạp lúc khởi động khi đặt {@code mission.config.component-registry.manifest-dir} (thư
 * mục {@code data/seed/component-registry} hoặc manifest sinh từ bản build shell).
 */
@Configuration(proxyBeanMethods = false)
public class ComponentRegistryConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ComponentRegistryConfiguration.class);

  @Bean
  JdbcComponentRegistryRepository componentRegistryRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcComponentRegistryRepository(JdbcClient.create(dataSource));
  }

  @Bean
  ComponentRegistryService componentRegistryService(
      JdbcComponentRegistryRepository componentRegistryRepository,
      ObjectProvider<PluginWidgets> plugins,
      ObjectProvider<ObjectMapper> json) {
    return new ComponentRegistryService(
        componentRegistryRepository,
        plugins.getIfAvailable(() -> PluginWidgets.NONE),
        json.getIfAvailable(ObjectMapper::new));
  }

  /** Bộ kiểm bố cục, chạy trước các bộ kiểm khác của UI Pack. */
  @Bean
  @Order(0)
  LayoutValidator layoutValidator(
      ComponentRegistryService componentRegistryService,
      ObjectProvider<ObjectMapper> json,
      @Value("${mission.config.ui-pack.relative-url-prefixes:/}") List<String> prefixes) {
    return new LayoutValidator(
        componentRegistryService, json.getIfAvailable(ObjectMapper::new), prefixes);
  }

  /** Lệnh nạp manifest: mỗi tệp {@code *.json} trong thư mục là một phiên bản registry. */
  @Bean
  @ConditionalOnProperty("mission.config.component-registry.manifest-dir")
  ApplicationRunner componentRegistryManifestLoader(
      ComponentRegistryService componentRegistryService,
      @Value("${mission.config.component-registry.manifest-dir}") Path dir) {
    return args -> loadManifests(componentRegistryService, dir);
  }

  static void loadManifests(ComponentRegistryService registry, Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
        ComponentRegistryService.LoadResult r =
            registry.load(Files.readString(file, StandardCharsets.UTF_8));
        LOG.info(
            "Registry SDUI {}: {} ({} component)",
            r.registryVersion(),
            r.loaded() ? "đã nạp" : "đã có, giữ nguyên",
            r.components());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
