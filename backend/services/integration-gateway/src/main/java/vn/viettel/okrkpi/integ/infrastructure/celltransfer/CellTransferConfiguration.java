package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.apache.camel.RoutesBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import vn.viettel.okrkpi.integ.application.CellTransferService;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory;
import vn.viettel.okrkpi.integ.application.port.CellTenants;
import vn.viettel.okrkpi.integ.application.port.PackageCrypto;
import vn.viettel.okrkpi.integ.application.port.TransferObjects;
import vn.viettel.okrkpi.integ.application.port.TransferPackages;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.integ.infrastructure.crypto.JcaPackageCrypto;
import vn.viettel.okrkpi.integ.infrastructure.crypto.PemKeys;
import vn.viettel.okrkpi.integ.infrastructure.hrm.ServiceToken;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcTransferPackages;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Gói chuyển hồ sơ cán bộ liên cell (DV-13-10), bật bằng {@code
 * okrkpi.integ.cell-transfer.enabled=true}: cần mã cell, khoá ký EC P-256, khoá nhận gói RSA, danh
 * bạ khoá cell có ký cùng khoá gốc, bucket {@code transfer} và địa chỉ cổng nội bộ của từng loại
 * phần.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CellTransferProperties.class)
@ConditionalOnProperty(name = "okrkpi.integ.cell-transfer.enabled", havingValue = "true")
public class CellTransferConfiguration {

  @Bean
  TransferPackages integTransferPackages(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
    return new JdbcTransferPackages(jdbc);
  }

  @Bean
  CellKeyDirectory integCellKeyDirectory(
      CellTransferProperties p, ResourceLoader resources, ObjectProvider<ObjectMapper> mapper) {
    CellTransferProperties.Directory d = p.directory();
    if (d.location() == null || d.location().isBlank() || d.rootKeyPath() == null) {
      throw new IllegalStateException("Thiếu vị trí danh bạ khoá cell hoặc khoá gốc");
    }
    return new SignedCellKeyDirectory(
        () -> {
          try (InputStream in = resources.getResource(d.location()).getInputStream()) {
            return in.readAllBytes();
          } catch (IOException e) {
            throw new IllegalStateException("Không đọc được danh bạ khoá cell", e);
          }
        },
        PemKeys.publicKey(Path.of(d.rootKeyPath()), "EC"),
        json(mapper),
        Clock.systemUTC(),
        d.refresh(),
        d.allowHttp());
  }

  @Bean
  PackageCrypto integPackageCrypto(CellTransferProperties p) {
    if (p.signingKeyPath() == null || p.decryptionKeyPath() == null) {
      throw new IllegalStateException("Thiếu khoá ký hoặc khoá nhận gói của cell");
    }
    return new JcaPackageCrypto(
        PemKeys.privateKey(Path.of(p.signingKeyPath()), "EC"),
        PemKeys.privateKey(Path.of(p.decryptionKeyPath()), "RSA"));
  }

  @Bean(destroyMethod = "close")
  S3Client integTransferS3(CellTransferProperties p) {
    CellTransferProperties.Storage s = p.storage();
    S3ClientBuilder builder =
        S3Client.builder()
            .region(Region.of(s.region()))
            .httpClient(UrlConnectionHttpClient.create())
            .forcePathStyle(true);
    if (s.endpoint() != null && !s.endpoint().isBlank()) {
      builder.endpointOverride(URI.create(s.endpoint()));
    }
    if (s.accessKey() != null && !s.accessKey().isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(s.accessKey(), s.secretKey())));
    }
    return builder.build();
  }

  @Bean
  TransferObjects integTransferObjects(CellTransferProperties p, S3Client integTransferS3) {
    return new S3TransferObjects(
        integTransferS3, p.storage().bucket(), p.storage().retention(), Clock.systemUTC());
  }

  /** Có bản sao danh bạ tenant (định tuyến cell bật) thì chỉ nhận gói cho tenant của cell này. */
  @Bean
  CellTenants integCellTenants(CellTransferProperties p, ObjectProvider<TenantDirectory> dir) {
    return tenantId -> {
      TenantDirectory d = dir.getIfAvailable();
      return d == null
          || d.findByTenantId(tenantId).map(r -> p.cellCode().equals(r.cellCode())).orElse(false);
    };
  }

  @Bean
  CellTransferService integCellTransferService(
      CellTransferProperties p,
      TransferPackages packages,
      TransferObjects objects,
      CellKeyDirectory directory,
      CellTenants tenants,
      PackageCrypto crypto,
      CanonicalEventSink sink,
      TransactionTemplate integTransactions,
      ObjectProvider<ObjectMapper> mapper,
      ObjectProvider<Clock> clock) {
    if (p.cellCode() == null || p.cellCode().isBlank()) {
      throw new IllegalStateException("Thiếu mã cell của gateway");
    }
    ObjectMapper json = json(mapper);
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    Clock c = clock.getIfAvailable(Clock::systemUTC);
    HttpTransferParts parts =
        new HttpTransferParts(
            http,
            new ServiceToken(
                http,
                json,
                p.token().tokenUrl(),
                p.token().clientId(),
                p.token().clientSecret(),
                c),
            p.parts(),
            p.timeout());
    return new CellTransferService(
        packages,
        objects,
        parts,
        parts,
        directory,
        tenants,
        new HttpCellChannel(http, json, p.timeout()),
        crypto,
        new JsonTransferCodec(json, p.maxBytes()),
        sink,
        integTransactions,
        c,
        new CellTransferService.Settings(
            p.cellCode(),
            new RetrySchedule(Duration.ofSeconds(30), 2, Duration.ofHours(1), 12),
            new RetrySchedule(Duration.ofSeconds(30), 2, Duration.ofHours(1), 12),
            p.maxBytes()));
  }

  @Bean
  @ConditionalOnProperty(
      name = "okrkpi.integ.cell-transfer.dispatch.enabled",
      havingValue = "true",
      matchIfMissing = true)
  RoutesBuilder integCellTransferRoute(CellTransferService service, CellTransferProperties p) {
    return new CellTransferRoute(service, p.dispatch().period(), p.dispatch().batch());
  }

  private static ObjectMapper json(ObjectProvider<ObjectMapper> mapper) {
    return mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build());
  }
}
