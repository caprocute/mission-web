package vn.viettel.okrkpi.task.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.viettel.okrkpi.contract.tasks.model.CatalogImportJob;
import vn.viettel.okrkpi.contract.tasks.model.CatalogImportJobErrorGroupsInner;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.catalog.CatalogImportService;

/**
 * Nhập danh mục công việc từ Excel (CORE-02-04; QT-04 → SCR-0401 "Nhập từ Excel" → {@code
 * tasks.yaml} {@code importCatalogExcel}, {@code getLatestCatalogImport}, {@code getCatalogImport},
 * {@code downloadCatalogImportErrors} → MOD-02 → {@code task.catalog_import_job}). Tác vụ dài theo
 * baseline mục 13: 202 kèm {@code Location} trỏ tới lần nhập.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogImportController {

  private static final String IMPORTS = "/api/v1/catalog/imports/";
  private static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

  private final CatalogImportService imports;

  public CatalogImportController(CatalogImportService imports) {
    this.imports = Objects.requireNonNull(imports, "imports");
  }

  @PostMapping(value = "/{catalogId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Idempotent
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "importCatalogExcel",
      module = "catalog",
      resourceType = "work_catalog")
  public ResponseEntity<CatalogImportJob> importCatalogExcel(
      @PathVariable("catalogId") UUID catalogId,
      @RequestPart("file") MultipartFile file,
      Principal principal)
      throws IOException {
    TenantId tenant = TenantContext.require();
    UUID actor = CatalogController.caller(principal);
    String name = fileName(file);
    Path tmp = Files.createTempFile("catalog-import-", ".xlsx");
    try {
      file.transferTo(tmp);
      requireXlsx(tmp);
      vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob job;
      try {
        job = imports.start(tenant, actor, catalogId, name, tmp);
      } catch (NoSuchElementException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      tmp = null;
      return ResponseEntity.accepted()
          .location(URI.create(IMPORTS + job.id()))
          .header(HttpHeaders.RETRY_AFTER, "2")
          .body(toDto(job));
    } finally {
      if (tmp != null) {
        Files.deleteIfExists(tmp);
      }
    }
  }

  @GetMapping("/{catalogId}/imports/latest")
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "getLatestCatalogImport",
      module = "catalog",
      resourceType = "work_catalog")
  public ResponseEntity<Object> getLatestCatalogImport(@PathVariable("catalogId") UUID catalogId) {
    TenantId tenant = TenantContext.require();
    return imports
        .latest(tenant, catalogId)
        .<ResponseEntity<Object>>map(j -> ResponseEntity.ok(toDto(j)))
        // Hợp đồng trả JSON null khi phiên bản chưa có lần nhập nào.
        .orElseGet(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null"));
  }

  @GetMapping("/imports/{jobId}")
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "getCatalogImport",
      module = "catalog",
      resourceType = "work_catalog")
  public CatalogImportJob getCatalogImport(@PathVariable("jobId") String jobId) {
    return toDto(found(TenantContext.require(), jobId));
  }

  @GetMapping("/imports/{jobId}/errors")
  @RequiresPermission(
      action = "SCR-0401.sua",
      operationId = "downloadCatalogImportErrors",
      module = "catalog",
      resourceType = "work_catalog")
  public ResponseEntity<StreamingResponseBody> downloadCatalogImportErrors(
      @PathVariable("jobId") String jobId) {
    TenantId tenant = TenantContext.require();
    vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob job = found(tenant, jobId);
    String base = job.fileName().replaceFirst("(?i)\\.xlsx$", "");
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(base + "-dong-loi.xlsx", StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(out -> TenantContext.run(tenant, () -> write(tenant, job.id(), out)));
  }

  private void write(TenantId tenant, UUID jobId, java.io.OutputStream out) {
    try {
      imports.writeErrors(tenant, jobId, out);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob found(
      TenantId tenant, String jobId) {
    try {
      return imports.find(tenant, UUID.fromString(jobId));
    } catch (IllegalArgumentException | NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static String fileName(MultipartFile file) {
    String name = file.getOriginalFilename();
    if (name == null || name.isBlank()) {
      name = "danh-muc.xlsx";
    }
    name = Path.of(name.replace('\\', '/')).getFileName().toString();
    if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw notXlsx();
    }
    return name.length() > 255 ? name.substring(name.length() - 255) : name;
  }

  private static void requireXlsx(Path file) throws IOException {
    byte[] head = new byte[ZIP_MAGIC.length];
    int read;
    try (InputStream in = Files.newInputStream(file)) {
      read = in.readNBytes(head, 0, head.length);
    }
    if (read < head.length || !Arrays.equals(head, ZIP_MAGIC)) {
      throw notXlsx();
    }
  }

  private static BusinessException notXlsx() {
    return new BusinessException(
        "COMMON-422-VALIDATION", "Tệp phải là bảng tính Excel (.xlsx) theo mẫu danh mục.");
  }

  static CatalogImportJob toDto(vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob j) {
    CatalogImportJob dto =
        new CatalogImportJob()
            .id(j.id().toString())
            .fileName(j.fileName())
            .status(CatalogImportJob.StatusEnum.fromValue(j.status().name()))
            .totalRows(j.totalRows())
            .validRows(j.validRows())
            .finishedAt(j.finishedAt() == null ? null : j.finishedAt().atOffset(ZoneOffset.UTC));
    j.errorGroups()
        .forEach(
            g ->
                dto.addErrorGroupsItem(
                    new CatalogImportJobErrorGroupsInner(
                        g.reason(),
                        g.count(),
                        CatalogImportJobErrorGroupsInner.KindEnum.fromValue(g.kind().name()))));
    return dto;
  }
}
