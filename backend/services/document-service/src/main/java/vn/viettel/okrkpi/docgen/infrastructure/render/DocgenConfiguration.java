package vn.viettel.okrkpi.docgen.infrastructure.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.docgen.application.render.BangChamDiemRenderer;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts;
import vn.viettel.okrkpi.docgen.application.render.FormPreviewService;
import vn.viettel.okrkpi.docgen.application.render.FormRenderer;
import vn.viettel.okrkpi.docgen.application.render.Mau1Renderer;
import vn.viettel.okrkpi.docgen.application.render.Mau2Renderer;
import vn.viettel.okrkpi.docgen.application.render.Mau3Renderer;
import vn.viettel.okrkpi.docgen.application.render.PreviewRateLimiter;
import vn.viettel.okrkpi.docgen.application.render.RenderEvents;
import vn.viettel.okrkpi.docgen.application.render.RenderInputPort;
import vn.viettel.okrkpi.docgen.application.render.RenderMetrics;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.docgen.application.render.SealedValueOpener;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobRepository;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Ghép đường ống sinh của SVC-11 (DV-11-01). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocgenConfiguration.DocgenProperties.class)
public class DocgenConfiguration {

  /**
   * Cấu hình {@code okrkpi.docgen}: Gotenberg, kho đối tượng S3, cổng nội bộ MOD-06, số lần thử và
   * thời hạn thuê một lượt sinh ({@code lease}). Khoá truy cập S3 chỉ lấy từ biến môi trường
   * (OpenBao ở production), không có giá trị mặc định.
   */
  @ConfigurationProperties(prefix = "okrkpi.docgen")
  public record DocgenProperties(
      Gotenberg gotenberg,
      Storage storage,
      String coreUrl,
      Integer maxAttempts,
      Duration lease,
      Preview preview) {
    public DocgenProperties {
      preview = preview == null ? new Preview(null, null, null, null, null) : preview;
      gotenberg = gotenberg == null ? new Gotenberg(null, null) : gotenberg;
      storage = storage == null ? new Storage(null, null, null, null, null) : storage;
      coreUrl = coreUrl == null ? "http://127.0.0.1:8080" : coreUrl;
      maxAttempts = maxAttempts == null ? 3 : maxAttempts;
      lease = lease == null ? Duration.ofMinutes(5) : lease;
    }

    /**
     * Xem trước (DV-11-02): {@code limit} lượt mỗi người trong {@code window}, quá {@code timeout}
     * thì 503; {@code threads} luồng trộn và chuyển PDF, hàng đợi {@code queue}.
     */
    public record Preview(
        Integer limit, Duration window, Duration timeout, Integer threads, Integer queue) {
      public Preview {
        limit = limit == null ? 20 : limit;
        window = window == null ? Duration.ofMinutes(1) : window;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        threads = threads == null ? 4 : threads;
        queue = queue == null ? 16 : queue;
      }
    }

    /** Địa chỉ Gotenberg 8 và thời hạn một lần chuyển. */
    public record Gotenberg(String url, Duration timeout) {
      public Gotenberg {
        url = url == null ? "http://127.0.0.1:3000" : url;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
      }
    }

    /** Kho đối tượng S3; {@code buckets} ánh xạ tên logic {@code templates}, {@code dossier}. */
    public record Storage(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        Map<String, String> buckets) {
      public Storage {
        region = region == null ? "us-east-1" : region;
        buckets = buckets == null ? Map.of() : buckets;
      }
    }
  }

  @Bean
  @ConditionalOnMissingBean
  Clock docgenClock() {
    return Clock.systemUTC();
  }

  @Bean
  TemplateRepository templateRepository(JdbcClient jdbc) {
    return new JdbcTemplateRepository(jdbc);
  }

  @Bean
  RenderJobRepository renderJobRepository(JdbcClient jdbc) {
    return new JdbcRenderJobRepository(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(RenderInputPort.class)
  RenderInputPort renderInputPort(ResilientClientFactory factory, DocgenProperties properties) {
    return new HttpRenderInputClient(factory.create("docgen-dossier", properties.coreUrl()));
  }

  @Bean
  @ConditionalOnMissingBean(DocumentPorts.DocxMerger.class)
  DocumentPorts.DocxMerger docxMerger() {
    return new Docx4jMerger();
  }

  @Bean
  @ConditionalOnMissingBean(DocumentPorts.PdfConverter.class)
  DocumentPorts.PdfConverter pdfConverter(DocgenProperties properties) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(properties.gotenberg().timeout());
    return new GotenbergConverter(
        RestClient.builder().baseUrl(properties.gotenberg().url()).requestFactory(factory).build());
  }

  @Bean
  @ConditionalOnMissingBean(DocumentPorts.PdfaValidator.class)
  DocumentPorts.PdfaValidator pdfaValidator() {
    return new VeraPdfValidator();
  }

  @Bean
  @ConditionalOnMissingBean(DocumentPorts.PdfStamper.class)
  DocumentPorts.PdfStamper pdfStamper() {
    return new PdfBoxStamper();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  S3Client docgenS3(DocgenProperties properties) {
    DocgenProperties.Storage storage = properties.storage();
    var builder =
        S3Client.builder()
            .region(Region.of(storage.region()))
            .httpClient(UrlConnectionHttpClient.create())
            .forcePathStyle(true);
    if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
      builder.endpointOverride(URI.create(storage.endpoint()));
    }
    if (storage.accessKey() != null && !storage.accessKey().isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())));
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean(DocumentPorts.ObjectStore.class)
  DocumentPorts.ObjectStore objectStore(S3Client s3, DocgenProperties properties) {
    return new S3ObjectStore(s3, properties.storage().buckets());
  }

  @Bean
  FormRenderer mau1aRenderer() {
    return new Mau1Renderer("MAU_1A");
  }

  @Bean
  FormRenderer mau1bRenderer() {
    return new Mau1Renderer("MAU_1B");
  }

  @Bean
  FormRenderer mau1cRenderer() {
    return new Mau1Renderer("MAU_1C");
  }

  @Bean
  FormRenderer mau1dRenderer() {
    return new Mau1Renderer("MAU_1D");
  }

  @Bean
  FormRenderer mau2Renderer() {
    return new Mau2Renderer();
  }

  @Bean
  FormRenderer mau3Renderer() {
    return new Mau3Renderer();
  }

  @Bean
  FormRenderer bangChamDiemRenderer() {
    return new BangChamDiemRenderer();
  }

  /**
   * Giải mã C2 của dữ liệu biểu mẫu: dùng {@link FieldEncryptor} khi thư viện nền bật mã hoá
   * ({@code okrkpi.crypto.enabled}); không có thì biểu mẫu mang trường C2 mã hoá bị từ chối.
   */
  @Bean
  @ConditionalOnMissingBean(SealedValueOpener.class)
  SealedValueOpener sealedValueOpener(ObjectProvider<FieldEncryptor> encryptor) {
    FieldEncryptor e = encryptor.getIfAvailable();
    return e == null ? SealedValueOpener.UNAVAILABLE : new FieldEncryptorSealedValueOpener(e);
  }

  @Bean
  RenderEvents renderEvents(OutboxWriter outbox) {
    return new OutboxRenderEvents(outbox);
  }

  @Bean
  RenderMetrics renderMetrics(ObjectProvider<MeterRegistry> registry) {
    MeterRegistry r = registry.getIfAvailable();
    return r == null ? RenderMetrics.NONE : new MicrometerRenderMetrics(r);
  }

  @Bean
  @SuppressWarnings("checkstyle:ParameterNumber")
  RenderService renderService(
      TemplateRepository templates,
      RenderJobRepository jobs,
      RenderInputPort inputs,
      ObjectProvider<FormRenderer> renderers,
      DocumentPorts.DocxMerger merger,
      DocumentPorts.PdfConverter converter,
      DocumentPorts.PdfaValidator validator,
      DocumentPorts.PdfStamper stamper,
      DocumentPorts.ObjectStore store,
      RenderEvents events,
      RenderMetrics metrics,
      ObjectMapper json,
      Clock clock,
      DocgenProperties properties,
      PlatformTransactionManager transactions,
      SealedValueOpener opener) {
    return new RenderService(
        templates,
        jobs,
        inputs,
        renderers.orderedStream().toList(),
        merger,
        converter,
        validator,
        stamper,
        store,
        events,
        metrics,
        json,
        clock,
        properties.maxAttempts(),
        properties.lease(),
        new TransactionTemplate(transactions),
        opener);
  }

  @Bean(destroyMethod = "shutdown")
  ThreadPoolExecutor docgenPreviewExecutor(DocgenProperties properties) {
    DocgenProperties.Preview p = properties.preview();
    AtomicInteger seq = new AtomicInteger();
    return new ThreadPoolExecutor(
        p.threads(),
        p.threads(),
        60,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(p.queue()),
        r -> {
          Thread t = new Thread(r, "docgen-preview-" + seq.incrementAndGet());
          t.setDaemon(true);
          return t;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  FormPreviewService formPreviewService(
      RenderService render,
      RenderInputPort inputs,
      DocumentPorts.PdfConverter converter,
      ThreadPoolExecutor docgenPreviewExecutor,
      DocgenProperties properties,
      Clock clock) {
    DocgenProperties.Preview p = properties.preview();
    return new FormPreviewService(
        render,
        inputs,
        converter,
        new PreviewRateLimiter(p.limit(), p.window(), clock),
        docgenPreviewExecutor,
        p.timeout());
  }

  @Bean
  JobSource renderJobSource(RenderService service) {
    return new RenderJobSource(service);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "okrkpi.docgen.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class WorkerConfiguration {

    @Bean
    PendingJobScanner pendingJobScanner(JdbcClient jdbc, ObjectProvider<MeterRegistry> registry) {
      PendingJobScanner scanner = new PendingJobScanner(jdbc);
      MeterRegistry r = registry.getIfAvailable();
      if (r != null) {
        Gauge.builder("docgen.render.queue", scanner, PendingJobScanner::queueLength)
            .description("Số tác vụ sinh đang chờ (chỉ số co giãn KEDA)")
            .register(r);
      }
      return scanner;
    }

    @Bean(destroyMethod = "close")
    RenderWorker renderWorker(
        PendingJobScanner scanner,
        RenderService service,
        DocgenProperties properties,
        @Value("${okrkpi.docgen.worker.batch-size:20}") int batchSize,
        @Value("${okrkpi.docgen.worker.concurrency:2}") int concurrency) {
      return new RenderWorker(scanner, service, batchSize, properties.lease(), concurrency);
    }
  }
}
