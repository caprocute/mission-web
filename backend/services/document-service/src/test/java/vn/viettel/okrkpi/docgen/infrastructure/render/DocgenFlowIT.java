package vn.viettel.okrkpi.docgen.infrastructure.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lowagie.text.pdf.PdfReader;
import io.swagger.v3.oas.models.PathItem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.STBrType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.contract.test.domain.ContractViolation;
import vn.viettel.okrkpi.docgen.application.render.Hashes;
import vn.viettel.okrkpi.docgen.application.render.RenderInputPort;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobStatus;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.docgen.domain.render.TemplateStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Đường ống sinh thật (DV-11-01): PostgreSQL 17, Gotenberg 8, kho S3 giả lập, veraPDF trong tiến
 * trình. Một lệnh sinh tạo đúng một tệp PDF/A-2b đạt veraPDF, {@code pdf_sha256} khớp tệp trong
 * kho, đúng một EVT-17 trong outbox; gửi lại cùng {@code Idempotency-Key} không sinh thêm; dữ liệu
 * lệch băm thì tác vụ {@code LOI} kèm lý do.
 */
@SpringBootTest(
    properties = {
      "okrkpi.docgen.worker.enabled=false",
      "okrkpi.docgen.auth-enabled=true",
      "okrkpi.docgen.storage.access-key=thu-nghiem",
      "okrkpi.docgen.storage.secret-key=thu-nghiem",
      "okrkpi.docgen.storage.buckets.templates=templates",
      "okrkpi.docgen.storage.buckets.dossier=dossier",
      "management.server.port=8080",
      "springdoc.paths-to-match=/api/v1/dossiers/**"
    })
@AutoConfigureMockMvc
@Testcontainers
class DocgenFlowIT extends ContractConformanceTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000d001");
  private static final UUID APPRAISER = UUID.fromString("0192f1a2-0000-7000-8000-00000000d002");

  static final Map<UUID, List<RenderInputPort.FormRef>> PACKAGES = new ConcurrentHashMap<>();
  static final Map<UUID, byte[]> DATA = new ConcurrentHashMap<>();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_docgen")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> GOTENBERG =
      new GenericContainer<>("gotenberg/gotenberg:8")
          .withExposedPorts(3000)
          .waitingFor(Wait.forHttp("/health").forPort(3000));

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:latest")
          .withExposedPorts(9090)
          .waitingFor(Wait.forListeningPort());

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add(
        "okrkpi.docgen.gotenberg.url",
        () -> "http://" + GOTENBERG.getHost() + ":" + GOTENBERG.getMappedPort(3000));
    registry.add(
        "okrkpi.docgen.storage.endpoint",
        () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    RenderInputPort fakeDossier() {
      return new RenderInputPort() {
        @Override
        public Optional<List<FormRef>> forms(
            UUID tenantId, UUID actor, UUID packageId, List<String> formCodes) {
          return Optional.ofNullable(PACKAGES.get(packageId));
        }

        @Override
        public Optional<FormData> data(UUID tenantId, UUID formInstanceId) {
          return Optional.ofNullable(DATA.get(formInstanceId))
              .map(d -> new FormData(formInstanceId, "MAU_3", d));
        }
      };
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private RenderService service;
  @Autowired private TemplateRepository templates;
  @Autowired private S3Client s3;

  private static boolean seeded;

  @BeforeAll
  static void reset() {
    seeded = false;
  }

  /** Mẫu thử tối giản hai trang có chỗ giữ, tải lên bucket templates và đăng ký phiên bản 1. */
  private void seedTemplate() throws Exception {
    if (seeded) {
      return;
    }
    for (String bucket : List.of("templates", "dossier")) {
      if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
        s3.createBucket(b -> b.bucket(bucket));
      }
    }
    WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
    var main = pkg.getMainDocumentPart();
    main.addStyledParagraphOfText("Title", "PHIẾU ĐÁNH GIÁ, XẾP LOẠI CHẤT LƯỢNG");
    main.addParagraphOfText("Họ và tên: ${canBo.hoTen}");
    main.addParagraphOfText("Chức vụ: ${canBo.chucVu}");
    main.addParagraphOfText("Tự đánh giá: ${tuDanhGia}");
    ObjectFactory factory = new ObjectFactory();
    P breakParagraph = factory.createP();
    R run = factory.createR();
    Br br = factory.createBr();
    br.setType(STBrType.PAGE);
    run.getContent().add(br);
    breakParagraph.getContent().add(run);
    main.getContent().add(breakParagraph);
    main.addParagraphOfText("Kết quả xếp loại: ${xepLoai}");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    pkg.save(out);
    byte[] docx = out.toByteArray();
    s3.putObject(
        b -> b.bucket("templates").key("MAU_3/v1.docx"),
        software.amazon.awssdk.core.sync.RequestBody.fromBytes(docx));
    TenantContext.run(
        TenantId.of(TENANT),
        () ->
            templates.insert(
                new Template(
                    UUID.randomUUID(),
                    TENANT,
                    "MAU_3",
                    1,
                    List.of(),
                    "MAU_3/v1.docx",
                    Hashes.sha256(docx),
                    TemplateStatus.HIEU_LUC),
                APPRAISER));
    seeded = true;
  }

  private static UUID dossierPackage(String hoTen) {
    UUID packageId = UUID.randomUUID();
    UUID formId = UUID.randomUUID();
    byte[] json =
        ("{\"canBo\":{\"hoTen\":\""
                + hoTen
                + "\",\"chucVu\":\"Chuyên viên\"},\"tuDanhGia\":\"Hoàn thành tốt nhiệm vụ\","
                + "\"xepLoai\":\"Hoàn thành tốt nhiệm vụ\"}")
            .getBytes(StandardCharsets.UTF_8);
    DATA.put(formId, json);
    PACKAGES.put(
        packageId,
        List.of(new RenderInputPort.FormRef(formId, "MAU_3", null, Hashes.sha256(json))));
    return packageId;
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String role) {
    return r.with(
        jwt()
            .jwt(j -> j.subject(APPRAISER.toString()).claim("tenant_id", TENANT.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  @Override
  protected ContractModule contractModule() {
    return ContractModule.DOSSIERS;
  }

  @Override
  protected String ownerServiceCode() {
    return "SVC-11";
  }

  /** Thao tác SVC-11 của task sau: sinh dự thảo và xem trước biểu mẫu (DV-11-02, DV-11-03). */
  private static final List<String> LATER_TASKS =
      List.of("POST /dossiers/forms", "POST /dossiers/forms/preview");

  @Test
  void apiAndAcceptedResponseMatchContract() throws Exception {
    List<String> violations =
        evaluateOpenApiDocs(mvc).violations().stream()
            .filter(
                v ->
                    !("MISSING_ENDPOINT".equals(v.category())
                        && LATER_TASKS.contains(v.location())))
            .map(ContractViolation::format)
            .toList();
    assertThat(violations).isEmpty();
    seedTemplate();
    String body =
        mvc.perform(
                as(post("/api/v1/dossiers/render-jobs"), "service_client")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"packageId\":\"" + dossierPackage("Hồ Thị Lan") + "\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                OpenApiConformanceEngine.loadExpectedSpec(ContractModule.DOSSIERS),
                "/api/v1/dossiers/render-jobs",
                PathItem.HttpMethod.POST,
                202,
                body))
        .isEmpty();
  }

  private String requestRender(UUID packageId, String key) throws Exception {
    return mvc.perform(
            as(post("/api/v1/dossiers/render-jobs"), "appraiser")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"" + packageId + "\",\"formCodes\":[\"MAU_3\"]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.type").value("RENDER"))
        .andExpect(jsonPath("$.status").value("CHO"))
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private static UUID idOf(String location) {
    return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
  }

  private RenderJobStatus process(UUID requestId) throws Exception {
    List<RenderJob> parts = TenantContext.call(TenantId.of(TENANT), () -> service.parts(requestId));
    assertThat(parts).hasSize(1);
    return TenantContext.call(TenantId.of(TENANT), () -> service.processOnce(parts.get(0).id()));
  }

  @Test
  void oneRequestProducesOneValidatedPdfaWithMatchingHashAndOneEvent() throws Exception {
    seedTemplate();
    // Khởi động LibreOffice trong Gotenberg trước khi đo.
    process(idOf(requestRender(dossierPackage("Lê Văn Khởi"), UUID.randomUUID().toString())));

    String key = UUID.randomUUID().toString();
    UUID packageId = dossierPackage("Nguyễn Thị Hoa");
    String location = requestRender(packageId, key);
    assertThat(location).startsWith("/api/v1/jobs/");
    assertThat(requestRender(packageId, key)).isEqualTo(location);
    UUID requestId = idOf(location);

    long started = System.nanoTime();
    assertThat(process(requestId)).isEqualTo(RenderJobStatus.XONG);
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    System.out.println("DV-11-01 đo: sinh phiếu hai trang mất " + tookMs + " ms");

    Map<String, Object> output =
        jdbc.sql(
                """
                SELECT o.object_key, o.pdf_sha256, o.size_bytes, o.pdfa_validated, o.output_kind
                  FROM docgen.render_output o JOIN docgen.render_job j ON j.id = o.render_job_id
                 WHERE j.request_id = ?
                """)
            .param(requestId)
            .query()
            .singleRow();
    byte[] stored =
        s3.getObjectAsBytes(b -> b.bucket("dossier").key((String) output.get("object_key")))
            .asByteArray();
    assertThat(Hashes.sha256(stored)).isEqualTo((byte[]) output.get("pdf_sha256"));
    assertThat(output.get("size_bytes")).isEqualTo((long) stored.length);
    assertThat(output.get("pdfa_validated")).isEqualTo(true);
    assertThat(output.get("output_kind")).isEqualTo("PDFA");
    assertThat(new VeraPdfValidator().validate(stored).compliant()).isTrue();
    PdfReader reader = new PdfReader(stored);
    assertThat(reader.getNumberOfPages()).isEqualTo(2);
    reader.close();

    Long events =
        jdbc.sql(
                "SELECT count(*) FROM docgen.outbox_event WHERE topic = ? AND aggregate_id IN"
                    + " (SELECT id FROM docgen.render_job WHERE request_id = ?)")
            .param("dossier.form.generated.v1")
            .param(requestId)
            .query(Long.class)
            .single();
    assertThat(events).isEqualTo(1);
    Long jobs =
        jdbc.sql("SELECT count(*) FROM docgen.render_job WHERE request_id = ?")
            .param(requestId)
            .query(Long.class)
            .single();
    assertThat(jobs).isEqualTo(1);

    mvc.perform(as(get(location), "appraiser"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HOAN_TAT"))
        .andExpect(jsonPath("$.progress").value(100));
  }

  @Test
  void changedInputFailsJobWithReason() throws Exception {
    seedTemplate();
    UUID packageId = dossierPackage("Phạm Văn Đức");
    UUID requestId = idOf(requestRender(packageId, UUID.randomUUID().toString()));
    UUID formId = PACKAGES.get(packageId).get(0).formInstanceId();
    DATA.put(formId, "{\"canBo\":{\"hoTen\":\"Đã sửa\"}}".getBytes(StandardCharsets.UTF_8));

    assertThat(process(requestId)).isEqualTo(RenderJobStatus.LOI);
    String error =
        jdbc.sql("SELECT error FROM docgen.render_job WHERE request_id = ?")
            .param(requestId)
            .query(String.class)
            .single();
    assertThat(error).contains("băm dữ liệu không khớp");
    mvc.perform(as(get("/api/v1/jobs/" + requestId), "appraiser"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("THAT_BAI"));
  }

  @Test
  void otherRolesAndUnknownPackagesAreRefused() throws Exception {
    mvc.perform(
            as(post("/api/v1/dossiers/render-jobs"), "staff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            as(post("/api/v1/dossiers/render-jobs"), "service_client")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(as(get("/api/v1/jobs/" + UUID.randomUUID()), "appraiser"))
        .andExpect(status().isNotFound());
  }
}
