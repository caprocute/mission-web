package vn.viettel.okrkpi.docgen.infrastructure.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.docgen.application.render.Hashes;
import vn.viettel.okrkpi.docgen.application.render.RenderInputPort;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.docgen.application.render.RenderStamp;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobStatus;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.docgen.domain.render.TemplateStatus;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Bảy biểu mẫu nghiệp vụ sinh thật qua Gotenberg 8 và veraPDF (DV-11-02, DV-11-03): mẫu giả lập
 * DL-05 có tệp kê khai, dữ liệu vàng {@code data/golden/docgen}. Kiểm văn bản trích từ PDF khớp kỳ
 * vọng, kỳ cũ giữ mẫu cũ khi có phiên bản mới, xem trước có dấu bản nháp trong khoảng 2 giây và có
 * giới hạn tốc độ, bản nháp không gắn gói, sinh lại sau khi mở lại kỳ tạo tệp mới mà tệp cũ còn
 * nguyên, không có dữ liệu C2 trong nhật ký, Mẫu 3 với 2.000 dòng.
 */
@SpringBootTest(
    properties = {
      "okrkpi.docgen.worker.enabled=false",
      "okrkpi.docgen.auth-enabled=true",
      "okrkpi.docgen.storage.access-key=thu-nghiem",
      "okrkpi.docgen.storage.secret-key=thu-nghiem",
      "okrkpi.docgen.storage.buckets.templates=templates",
      "okrkpi.docgen.storage.buckets.dossier=dossier",
      "okrkpi.docgen.preview.limit=12",
      "okrkpi.docgen.preview.timeout=20s",
      "okrkpi.docgen.gotenberg.timeout=300s",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(DocgenTestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class FormTemplatesIT {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000d101");
  static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-00000000d102");
  static final String PACK = "KHOI_DANG";
  static final String PACK_VERSION = "2.0.0";
  static final ObjectMapper JSON = new ObjectMapper();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_docgen")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> GOTENBERG =
      new GenericContainer<>("gotenberg/gotenberg:8")
          .withCommand("gotenberg", "--api-timeout=300s")
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

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private RenderService service;
  @Autowired private TemplateRepository templates;
  @Autowired private S3Client s3;

  private static boolean seeded;

  private void buckets() {
    for (String bucket : List.of("templates", "dossier")) {
      if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
        s3.createBucket(b -> b.bucket(bucket));
      }
    }
  }

  /** Đăng ký phiên bản 1 (đang hiệu lực) của bảy mẫu giả lập kèm tệp kê khai. */
  private void seed() {
    if (seeded) {
      return;
    }
    buckets();
    for (String code : GoldenForms.CODES) {
      register(code, 1, GoldenForms.docx(code), TemplateStatus.HIEU_LUC);
    }
    seeded = true;
  }

  private UUID register(String code, int version, byte[] docx, TemplateStatus status) {
    byte[] manifest = GoldenForms.manifest(code);
    String key = code + "/v" + version;
    s3.putObject(b -> b.bucket("templates").key(key + ".docx"), RequestBody.fromBytes(docx));
    s3.putObject(b -> b.bucket("templates").key(key + ".json"), RequestBody.fromBytes(manifest));
    UUID id = UUID.randomUUID();
    TenantContext.run(
        TenantId.of(TENANT),
        () ->
            templates.insert(
                new Template(
                    id,
                    TENANT,
                    code,
                    version,
                    List.of(),
                    key + ".docx",
                    Hashes.sha256(docx),
                    status,
                    key + ".json",
                    Hashes.sha256(manifest)),
                ACTOR));
    return id;
  }

  /** Gói hồ sơ có một biểu mẫu mỗi mã, dữ liệu vàng đã niêm phong C2. */
  private UUID dossierPackage(List<String> codes, Integer templateVersion, UUID reopen) {
    UUID packageId = UUID.randomUUID();
    List<RenderInputPort.FormRef> forms = new ArrayList<>();
    for (String code : codes) {
      UUID formId = UUID.randomUUID();
      byte[] data = GoldenForms.sealedInput(code, formId);
      DocgenTestBeans.DATA.put(formId, data);
      forms.add(
          new RenderInputPort.FormRef(
              formId,
              code,
              null,
              Hashes.sha256(data),
              templateVersion,
              PACK,
              PACK_VERSION,
              reopen));
    }
    DocgenTestBeans.PACKAGES.put(packageId, forms);
    return packageId;
  }

  private UUID render(UUID packageId) throws Exception {
    UUID requestId = UUID.randomUUID();
    TenantContext.call(
        TenantId.of(TENANT),
        () ->
            service.request(
                new RenderService.RequestCommand(TENANT, ACTOR, packageId, List.of(), requestId)));
    processAll(requestId);
    return requestId;
  }

  private void processAll(UUID requestId) throws Exception {
    boolean pending = true;
    while (pending) {
      for (RenderJob part : parts(requestId)) {
        TenantContext.call(TenantId.of(TENANT), () -> service.processOnce(part.id()));
      }
      pending = parts(requestId).stream().anyMatch(p -> p.status() == RenderJobStatus.CHO);
    }
  }

  private List<RenderJob> parts(UUID requestId) throws Exception {
    return TenantContext.call(TenantId.of(TENANT), () -> service.parts(requestId));
  }

  /** Tệp đã sinh của một lệnh theo mã biểu mẫu. */
  private List<Map<String, Object>> outputs(UUID requestId) {
    return jdbc.sql(
            """
            SELECT t.form_code, j.id AS job_id, j.form_instance_id, j.package_id, j.is_draft,
                   j.reopen_request_id, j.status, j.error, o.id AS output_id, o.object_key,
                   o.pdf_sha256, o.pdfa_validated
              FROM docgen.render_job j
              JOIN docgen.template t ON t.id = j.template_id
              LEFT JOIN docgen.render_output o ON o.render_job_id = j.id
             WHERE j.request_id = ?
             ORDER BY t.form_code
            """)
        .param(requestId)
        .query()
        .listOfRows();
  }

  private byte[] stored(Map<String, Object> output) {
    return s3.getObjectAsBytes(b -> b.bucket("dossier").key((String) output.get("object_key")))
        .asByteArray();
  }

  static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /** Các dòng kỳ vọng xuất hiện theo đúng thứ tự trong văn bản trích. */
  static void assertInOrder(String code, String text, List<String> expected) {
    // Ô bảng trong PDF xuống dòng theo độ rộng cột, nên so sánh sau khi gộp khoảng trắng.
    String flat = text.replaceAll("\\s+", " ");
    int from = 0;
    for (String expectedLine : expected) {
      String line = expectedLine.replaceAll("\\s+", " ");
      int at = flat.indexOf(line, from);
      assertThat(at).as("%s: dòng kỳ vọng \"%s\" sau vị trí %d", code, line, from).isNotNegative();
      from = at + line.length();
    }
  }

  private static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder r, UUID subject, String role) {
    return r.with(
        jwt()
            .jwt(j -> j.subject(subject.toString()).claim("tenant_id", TENANT.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  @Test
  void bayBieuMauSinhTuDuLieuVangDatPdfaVaKhopVanBanKyVong(CapturedOutput logs) throws Exception {
    seed();
    UUID requestId = render(dossierPackage(GoldenForms.CODES, 1, null));
    List<Map<String, Object>> outputs = outputs(requestId);
    assertThat(outputs).hasSize(GoldenForms.CODES.size());
    Path dump = Path.of("target/golden-pdf");
    Files.createDirectories(dump);
    for (Map<String, Object> output : outputs) {
      String code = (String) output.get("form_code");
      assertThat(output.get("status")).as(code + ": " + output.get("error")).isEqualTo("XONG");
      assertThat(output.get("pdfa_validated")).isEqualTo(true);
      byte[] pdf = stored(output);
      assertThat(new VeraPdfValidator().validate(pdf).compliant()).isTrue();
      String text = text(pdf);
      Files.writeString(dump.resolve(code + ".txt"), text);
      assertInOrder(code, text, GoldenForms.expected(code));
      assertThat(text).doesNotContain("[", "BẢN NHÁP");
    }
    for (String code : GoldenForms.CODES) {
      for (String secret : GoldenForms.plainSealedValues(code)) {
        assertThat(logs.getAll()).doesNotContain(secret.lines().findFirst().orElseThrow());
      }
    }
  }

  @Test
  void duLieuC2SaiTruongLamTacVuLoiMaKhongLoGiaTri(CapturedOutput logs) throws Exception {
    seed();
    UUID packageId = UUID.randomUUID();
    UUID formId = UUID.randomUUID();
    byte[] data = GoldenForms.sealedInput("MAU_1B", UUID.randomUUID());
    DocgenTestBeans.DATA.put(formId, data);
    DocgenTestBeans.PACKAGES.put(
        packageId,
        List.of(
            new RenderInputPort.FormRef(
                formId, "MAU_1B", null, Hashes.sha256(data), 1, PACK, PACK_VERSION)));
    Map<String, Object> output = outputs(render(packageId)).get(0);
    assertThat(output.get("status")).isEqualTo("LOI");
    for (String secret : GoldenForms.plainSealedValues("MAU_1B")) {
      String first = secret.lines().findFirst().orElseThrow();
      assertThat((String) output.get("error")).doesNotContain(first);
      assertThat(logs.getAll()).doesNotContain(first);
    }
  }

  /** Mẫu 1B có phiên bản 2 cho kỳ mới: kỳ cũ ghim phiên bản 1 sinh lại vẫn ra đúng nội dung cũ. */
  @Test
  void phienBanMauMoiKhongDoiTepCuaKyCu() throws Exception {
    seed();
    String before =
        text(stored(outputs(render(dossierPackage(List.of("MAU_1B"), 1, null))).get(0)));
    byte[] v2 = replaceInDocument(GoldenForms.docx("MAU_1B"), "MẪU 1B", "MẪU 1B – BẢN SỬA ĐỔI");
    register("MAU_1B", 2, v2, TemplateStatus.HIEU_LUC);
    jdbc.sql(
            "UPDATE docgen.template SET status = 'NGUNG' WHERE form_code = 'MAU_1B' AND version_no"
                + " = 1")
        .update();
    String oldPeriod =
        text(stored(outputs(render(dossierPackage(List.of("MAU_1B"), 1, null))).get(0)));
    String newPeriod =
        text(stored(outputs(render(dossierPackage(List.of("MAU_1B"), null, null))).get(0)));
    assertThat(oldPeriod).isEqualTo(before).doesNotContain("BẢN SỬA ĐỔI");
    assertThat(newPeriod).contains("MẪU 1B – BẢN SỬA ĐỔI").contains("Phiên bản mẫu biểu: 2");
  }

  @Test
  void xemTruocMau1bCoDauBanNhapTrongHaiGiayVaGioiHanTocDo() throws Exception {
    seed();
    UUID source = UUID.randomUUID();
    byte[] data = GoldenForms.sealedInput("MAU_1B", source);
    DocgenTestBeans.PREVIEWS.put(
        source, new RenderInputPort.PreviewData("MAU_1B", null, 1, PACK, PACK_VERSION, data));
    UUID member = UUID.randomUUID();
    String body =
        "{\"formCode\":\"MAU_1B\",\"sourceModule\":\"appraisal\",\"sourceRefId\":\""
            + source
            + "\"}";
    long[] ms = new long[6];
    byte[] pdf = null;
    for (int i = 0; i < ms.length; i++) {
      long started = System.nanoTime();
      pdf =
          mvc.perform(
                  as(post("/api/v1/dossiers/forms/preview"), member, "member")
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andExpect(status().isOk())
              .andExpect(header().string("Content-Type", "application/pdf"))
              .andReturn()
              .getResponse()
              .getContentAsByteArray();
      ms[i] = (System.nanoTime() - started) / 1_000_000;
    }
    long[] warm = Arrays.copyOfRange(ms, 1, ms.length);
    Arrays.sort(warm);
    long median = warm[warm.length / 2];
    System.out.println(
        "Xem trước Mẫu 1B (ms): " + Arrays.toString(ms) + ", trung vị lượt nóng " + median);
    assertThat(median).isLessThanOrEqualTo(2000);
    String text = text(pdf);
    assertThat(text)
        .contains("BẢN NHÁP")
        .contains(RenderStamp.DRAFT_LABEL)
        .contains("Chủ động tham mưu");
    assertThat(jdbc.sql("SELECT count(*) FROM docgen.render_output").query(Long.class).single())
        .isNotNull();

    // Vượt giới hạn: 12 lượt mỗi phút cho một người.
    UUID busy = UUID.randomUUID();
    int ok = 0;
    for (int i = 0; i < 12; i++) {
      ok +=
          mvc.perform(
                          as(post("/api/v1/dossiers/forms/preview"), busy, "appraiser")
                              .header("Idempotency-Key", UUID.randomUUID().toString())
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn()
                      .getResponse()
                      .getStatus()
                  == 200
              ? 1
              : 0;
    }
    assertThat(ok).isEqualTo(12);
    mvc.perform(
            as(post("/api/v1/dossiers/forms/preview"), busy, "appraiser")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));

    // Không có dữ liệu nguồn: 404; vai trò ngoài ma trận: 403.
    mvc.perform(
            as(post("/api/v1/dossiers/forms/preview"), member, "member")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(source.toString(), UUID.randomUUID().toString())))
        .andExpect(status().isNotFound());
    mvc.perform(
            as(post("/api/v1/dossiers/forms/preview"), member, "decider")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void banNhapMau3KhongGanGoiVaCoDauBanNhap() throws Exception {
    seed();
    UUID period = UUID.randomUUID();
    UUID formId = UUID.randomUUID();
    byte[] data = GoldenForms.sealedInput("MAU_3", formId);
    DocgenTestBeans.DATA.put(formId, data);
    DocgenTestBeans.DRAFTS.put(
        period,
        List.of(
            new RenderInputPort.FormRef(
                formId, "MAU_3", null, Hashes.sha256(data), 1, PACK, PACK_VERSION)));
    UUID decider = UUID.randomUUID();
    String response =
        mvc.perform(
                as(post("/api/v1/dossiers/forms"), decider, "decider")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"formCode\":\"MAU_3\",\"periodId\":\""
                            + period
                            + "\",\"profileIds\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID requestId = UUID.fromString(JSON.readTree(response).path("id").asText());
    processAll(requestId);
    Job job = TenantContext.call(TenantId.of(TENANT), () -> service.job(requestId)).orElseThrow();
    assertThat(job.status()).isEqualTo(JobStatus.HOAN_TAT);
    assertThat(job.resultLocation()).isEqualTo("/api/v1/dossiers/forms/" + formId);
    Map<String, Object> output = outputs(requestId).get(0);
    assertThat(output.get("package_id")).isNull();
    assertThat(output.get("is_draft")).isEqualTo(true);
    byte[] pdf = stored(output);
    assertThat(new VeraPdfValidator().validate(pdf).compliant()).isTrue();
    assertThat(text(pdf)).contains("BẢN NHÁP", RenderStamp.DRAFT_LABEL);
    assertThat(PdfBoxStamper.read(pdf).orElseThrow())
        .containsEntry(RenderStamp.DRAFT, "true")
        .containsEntry(RenderStamp.PACKAGE_ID, "");

    mvc.perform(
            as(post("/api/v1/dossiers/forms"), decider, "appraiser")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"formCode\":\"MAU_3\",\"periodId\":\""
                        + period
                        + "\",\"profileIds\":[\""
                        + UUID.randomUUID()
                        + "\"]}"))
        .andExpect(status().isForbidden());
  }

  /** BR-13-08: sinh lại sau khi mở lại kỳ tạo tệp mới, tệp và {@code pdf_sha256} cũ không đổi. */
  @Test
  void sinhLaiSauMoLaiKyTaoTepMoiGiuTepCu() throws Exception {
    seed();
    UUID packageId = dossierPackage(List.of("MAU_2", "MAU_3"), 1, null);
    UUID first = render(packageId);
    List<Map<String, Object>> old = outputs(first);
    List<byte[]> oldFiles = old.stream().map(this::stored).toList();

    UUID reopen = UUID.randomUUID();
    List<RenderInputPort.FormRef> again =
        DocgenTestBeans.PACKAGES.get(packageId).stream()
            .map(
                f ->
                    new RenderInputPort.FormRef(
                        f.formInstanceId(),
                        f.formCode(),
                        null,
                        f.dataSnapshotHash(),
                        1,
                        PACK,
                        PACK_VERSION,
                        reopen))
            .toList();
    UUID reopenedPackage = UUID.randomUUID();
    DocgenTestBeans.PACKAGES.put(reopenedPackage, again);
    List<Map<String, Object>> fresh = outputs(render(reopenedPackage));
    assertThat(fresh).hasSize(2);
    for (int i = 0; i < old.size(); i++) {
      Map<String, Object> before = old.get(i);
      Map<String, Object> after = fresh.get(i);
      assertThat(after.get("form_instance_id")).isEqualTo(before.get("form_instance_id"));
      assertThat(after.get("output_id")).isNotEqualTo(before.get("output_id"));
      assertThat(after.get("object_key")).isNotEqualTo(before.get("object_key"));
      assertThat(after.get("reopen_request_id")).isEqualTo(reopen);
      Map<String, Object> reread =
          jdbc.sql("SELECT object_key, pdf_sha256 FROM docgen.render_output WHERE id = ?")
              .param(before.get("output_id"))
              .query()
              .singleRow();
      assertThat(reread.get("object_key")).isEqualTo(before.get("object_key"));
      assertThat((byte[]) reread.get("pdf_sha256")).isEqualTo((byte[]) before.get("pdf_sha256"));
      assertThat(stored(before)).isEqualTo(oldFiles.get(i));
      assertThat(Hashes.sha256(stored(before))).isEqualTo((byte[]) before.get("pdf_sha256"));
      byte[] pdf = stored(after);
      assertThat(PdfBoxStamper.read(pdf).orElseThrow())
          .containsEntry(RenderStamp.REOPEN_REQUEST_ID, reopen.toString());
      assertThat(text(pdf)).contains("Sinh lại sau khi mở lại kỳ");
    }
  }

  @Test
  void mau3HaiNghinDongSinhXongTrongGioiHanBoNho() throws Exception {
    seed();
    ObjectNode data = GoldenForms.input("MAU_3");
    ArrayNode rows = data.putArray("danh_sach_xep_loai");
    String[] levels = {"HTXSNV", "HTTNV", "HTNV", "KHTNV"};
    for (int i = 0; i < 2000; i++) {
      ObjectNode r = rows.addObject();
      r.put("thu_tu_don_vi", i % 9);
      r.put("ten_don_vi", "Đơn vị " + (i % 9));
      r.put("ho_ten", "Cán bộ số " + i);
      r.put("chuc_danh", "Chuyên viên");
      r.put("muc_tu_de_xuat", levels[i % 4]);
      r.put("muc_tham_dinh", levels[(i + 1) % 4]);
      r.put("muc_quyet_dinh", levels[(i + 1) % 4]);
      r.put("ket_qua_bieu_quyet", "12/12 tán thành");
    }
    UUID formId = UUID.randomUUID();
    byte[] json = GoldenForms.sealed("MAU_3", data, formId);
    DocgenTestBeans.DATA.put(formId, json);
    UUID packageId = UUID.randomUUID();
    DocgenTestBeans.PACKAGES.put(
        packageId,
        List.of(
            new RenderInputPort.FormRef(
                formId, "MAU_3", null, Hashes.sha256(json), 1, PACK, PACK_VERSION)));
    List<MemoryPoolMXBean> heap =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(p -> p.getType() == MemoryType.HEAP)
            .toList();
    System.gc();
    heap.forEach(MemoryPoolMXBean::resetPeakUsage);
    long started = System.nanoTime();
    Map<String, Object> output = outputs(render(packageId)).get(0);
    long seconds = (System.nanoTime() - started) / 1_000_000_000;
    long peak = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
    long max = Runtime.getRuntime().maxMemory();
    assertThat(output.get("status")).as((String) output.get("error")).isEqualTo("XONG");
    byte[] pdf = stored(output);
    int pages;
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      pages = doc.getNumberOfPages();
    }
    System.out.printf(
        "Mẫu 3, 2000 dòng: %d giây, %d trang, %d KB, đỉnh heap %d MB / giới hạn %d MB%n",
        seconds, pages, pdf.length / 1024, peak >> 20, max >> 20);
    assertThat(text(pdf).replaceAll("\\s+", " ")).contains("Cán bộ số 1999");
    assertThat(pages).isGreaterThan(20);
    assertThat(peak).isLessThan(max);
  }

  /** Thay một đoạn chữ trong {@code word/document.xml} của tệp DOCX. */
  static byte[] replaceInDocument(byte[] docx, String from, String to) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        byte[] content = in.readAllBytes();
        if (e.getName().equals("word/document.xml")) {
          content =
              new String(content, StandardCharsets.UTF_8)
                  .replace(from, to)
                  .getBytes(StandardCharsets.UTF_8);
        }
        zip.putNextEntry(new ZipEntry(e.getName()));
        zip.write(content);
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }
}
