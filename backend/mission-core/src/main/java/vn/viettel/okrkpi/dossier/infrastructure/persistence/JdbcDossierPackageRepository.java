package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageCode;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link DossierPackageRepository} trên {@code dossier.dossier_package} và {@code
 * dossier.form_instance}.
 *
 * <p>Tra theo mã khi chưa biết kỳ (EVT-17 chỉ mang mã biểu mẫu, API chỉ mang mã gói): khoá chính
 * bắt đầu bằng {@code period_bucket} nên câu lệnh lọc {@code period_bucket = ANY(<các phân vùng
 * hiện có>)} để mỗi phân vùng dùng được chỉ mục khoá chính thay vì quét cả chỉ mục; danh sách phân
 * vùng đọc ở {@code pg_inherits} và nhớ trong một phút, không thấy thì đọc lại một lần.
 *
 * <p>Mã gói: khoá tư vấn theo (tenant, tiền tố {@code DP-yyyy-{Q/N}-}) trong giao dịch, kiểm lại
 * gói của đơn vị rồi lấy số lớn nhất cộng một, nên lập đồng thời không trùng mã và không lập gói
 * thứ hai cho cùng đơn vị, kỳ, loại.
 */
public class JdbcDossierPackageRepository implements DossierPackageRepository {

  private static final String PACKAGE_COLUMNS =
      "id, period_id, period_bucket, code, org_unit_id, person_id, package_kind, version_no, status,"
          + " deadline, submitted_at, transferred_to, sign_request_id, manifest_hash, package_sha256,"
          + " updated_by, row_version";

  private static final String FORM_COLUMNS =
      "id, period_id, period_bucket, dossier_package_id, profile_id, form_code, template_version,"
          + " data_snapshot_hash, render_job_id, object_key, pdf_sha256, status, signed_at";

  private static final Pattern BOUND = Pattern.compile("-?\\d+");
  private static final Duration BUCKET_TTL = Duration.ofMinutes(1);
  private static final String FROZEN_STATE = "55000";

  private final JdbcClient jdbc;
  private final ConcurrentHashMap<String, Buckets> buckets = new ConcurrentHashMap<>();

  private record Buckets(Integer[] values, Instant loadedAt) {}

  public JdbcDossierPackageRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  // ---------------------------------------------------------------- gói

  @Override
  public Optional<PackageRow> find(TenantId tenant, UUID id) {
    return byId("dossier.dossier_package", PACKAGE_COLUMNS, tenant, id, this::packageRow);
  }

  @Override
  public Optional<PackageRow> lock(TenantId tenant, int periodBucket, UUID id) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + " FROM dossier.dossier_package WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ? FOR UPDATE")
        .params(periodBucket, tenant.value(), id)
        .query(this::packageRow)
        .optional();
  }

  @Override
  public Optional<PackageRow> findByUnit(
      TenantId tenant, int periodBucket, UUID orgUnitId, PackageKind kind) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + " FROM dossier.dossier_package WHERE period_bucket = ? AND tenant_id = ?"
                + " AND org_unit_id = ? AND package_kind = ? AND person_id IS NULL"
                + " ORDER BY version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), orgUnitId, kind.name())
        .query(this::packageRow)
        .optional();
  }

  @Override
  public Ensured ensure(TenantId tenant, NewPackage cmd, UUID id, UUID actor) {
    Optional<PackageRow> existing =
        findByUnit(tenant, cmd.periodBucket(), cmd.orgUnitId(), cmd.kind());
    if (existing.isPresent()) {
      return new Ensured(existing.get(), false);
    }
    int year = PackageCode.yearOf(cmd.periodBucket());
    String prefix = PackageCode.prefix(year, cmd.kind());
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param(tenant.value() + ":" + prefix)
        .query()
        .singleRow();
    existing = findByUnit(tenant, cmd.periodBucket(), cmd.orgUnitId(), cmd.kind());
    if (existing.isPresent()) {
      return new Ensured(existing.get(), false);
    }
    Integer max =
        jdbc.sql(
                "SELECT coalesce(max(substring(code from ?)::int), 0) FROM dossier.dossier_package"
                    + " WHERE tenant_id = ? AND code LIKE ?")
            .params(prefix.length() + 1, tenant.value(), prefix + "%")
            .query(Integer.class)
            .single();
    String code = PackageCode.of(year, cmd.kind(), max + 1);
    jdbc.sql(
            """
            INSERT INTO dossier.dossier_package (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, code, org_unit_id, package_kind, status, deadline)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DANG_LAP', ?)
            """)
        .params(
            id,
            tenant.value(),
            actor,
            actor,
            cmd.periodId(),
            cmd.periodBucket(),
            code,
            cmd.orgUnitId(),
            cmd.kind().name(),
            cmd.deadline())
        .update();
    return new Ensured(lock(tenant, cmd.periodBucket(), id).orElseThrow(), true);
  }

  @Override
  public List<PackageRow> list(TenantId tenant, Filter filter, UUID after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(PACKAGE_COLUMNS)
            .append(" FROM dossier.dossier_package WHERE tenant_id = :tenant");
    if (filter.periodId() != null) {
      sql.append(" AND period_id = :period");
    }
    if (filter.orgUnitId() != null) {
      sql.append(" AND org_unit_id = :unit");
    }
    if (filter.kind() != null) {
      sql.append(" AND package_kind = :kind");
    }
    if (filter.status() != null) {
      sql.append(" AND status = :status");
    }
    if (after != null) {
      sql.append(" AND id > :after");
    }
    sql.append(" ORDER BY id LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("tenant", tenant.value()).param("limit", limit);
    if (filter.periodId() != null) {
      spec = spec.param("period", filter.periodId());
    }
    if (filter.orgUnitId() != null) {
      spec = spec.param("unit", filter.orgUnitId());
    }
    if (filter.kind() != null) {
      spec = spec.param("kind", filter.kind().name());
    }
    if (filter.status() != null) {
      spec = spec.param("status", filter.status().name());
    }
    if (after != null) {
      spec = spec.param("after", after);
    }
    return spec.query(this::packageRow).list();
  }

  @Override
  public List<PackageRow> generating(TenantId tenant, UUID after, int limit) {
    return list(tenant, new Filter(null, null, null, PackageStatus.DANG_SINH), after, limit);
  }

  @Override
  public long updateStatus(
      TenantId tenant, PackageRow row, PackageStatus next, Long expectedVersion, UUID actor) {
    String sql =
        "UPDATE dossier.dossier_package SET status = ?, row_version = row_version + 1,"
            + " updated_at = now(), updated_by = ? WHERE period_bucket = ? AND tenant_id = ?"
            + " AND id = ?"
            + (expectedVersion == null ? "" : " AND row_version = ?")
            + " RETURNING row_version";
    List<Object> params =
        new ArrayList<>(
            java.util.Arrays.asList(
                next.name(), actor, row.periodBucket(), tenant.value(), row.id()));
    if (expectedVersion != null) {
      params.add(expectedVersion.intValue());
    }
    return frozen(() -> jdbc.sql(sql).params(params).query(Long.class).optional().orElse(0L));
  }

  @Override
  public RenderProgress progress(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status IN ('DA_SINH', 'DA_KY')) AS rendered,
                   count(*) FILTER (WHERE status = 'LOI') AS failed,
                   count(*) FILTER (WHERE status = 'DA_KY') AS signed
              FROM dossier.form_instance
             WHERE period_bucket = ? AND tenant_id = ? AND dossier_package_id = ?
            """)
        .params(periodBucket, tenant.value(), packageId)
        .query(
            (rs, i) ->
                new RenderProgress(
                    rs.getInt("total"),
                    rs.getInt("rendered"),
                    rs.getInt("failed"),
                    rs.getInt("signed")))
        .single();
  }

  @Override
  public int profileCount(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            "SELECT count(DISTINCT profile_id) FROM dossier.form_instance"
                + " WHERE period_bucket = ? AND tenant_id = ? AND dossier_package_id = ?")
        .params(periodBucket, tenant.value(), packageId)
        .query(Integer.class)
        .single();
  }

  // ---------------------------------------------------------------- biểu mẫu

  @Override
  public Optional<FormRow> findForm(TenantId tenant, UUID formId) {
    return byId("dossier.form_instance", FORM_COLUMNS, tenant, formId, this::formRow);
  }

  @Override
  public List<FormRow> forms(
      TenantId tenant,
      int periodBucket,
      UUID packageId,
      FormStatus status,
      String formCode,
      UUID after,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(FORM_COLUMNS)
            .append(
                " FROM dossier.form_instance WHERE period_bucket = :bucket AND tenant_id = :tenant"
                    + " AND dossier_package_id = :pkg");
    if (status != null) {
      sql.append(" AND status = :status");
    }
    if (formCode != null) {
      sql.append(" AND form_code = :code");
    }
    if (after != null) {
      sql.append(" AND id > :after");
    }
    sql.append(" ORDER BY id LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("bucket", periodBucket)
            .param("tenant", tenant.value())
            .param("pkg", packageId)
            .param("limit", limit);
    if (status != null) {
      spec = spec.param("status", status.name());
    }
    if (formCode != null) {
      spec = spec.param("code", formCode);
    }
    if (after != null) {
      spec = spec.param("after", after);
    }
    return spec.query(this::formRow).list();
  }

  @Override
  public List<FormRow> formsOfProfile(
      TenantId tenant, int periodBucket, UUID packageId, UUID profileId) {
    return jdbc.sql(
            "SELECT "
                + FORM_COLUMNS
                + " FROM dossier.form_instance WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? AND dossier_package_id = ?")
        .params(periodBucket, tenant.value(), profileId, packageId)
        .query(this::formRow)
        .list();
  }

  @Override
  public List<FormRow> formsIn(
      TenantId tenant, int periodBucket, UUID packageId, Collection<FormStatus> statuses) {
    String sql =
        "SELECT "
            + FORM_COLUMNS
            + " FROM dossier.form_instance WHERE period_bucket = :bucket AND tenant_id = :tenant"
            + " AND dossier_package_id = :pkg"
            + (statuses.isEmpty() ? "" : " AND status IN (:statuses)")
            + " ORDER BY id";
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql)
            .param("bucket", periodBucket)
            .param("tenant", tenant.value())
            .param("pkg", packageId);
    if (!statuses.isEmpty()) {
      spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
    }
    return spec.query(this::formRow).list();
  }

  @Override
  public int insertForms(TenantId tenant, PackageRow pkg, List<NewForm> forms, UUID actor) {
    int inserted = 0;
    for (NewForm f : forms) {
      String guard = f.unitLevel() ? " AND form_code = ?" : " AND profile_id = ? AND form_code = ?";
      List<Object> params =
          new ArrayList<>(
              List.of(
                  f.id(),
                  tenant.value(),
                  actor,
                  actor,
                  pkg.periodId(),
                  pkg.periodBucket(),
                  pkg.id(),
                  f.profileId(),
                  f.formCode(),
                  f.templateVersion(),
                  f.dataSnapshotHash(),
                  pkg.periodBucket(),
                  tenant.value(),
                  pkg.id()));
      if (!f.unitLevel()) {
        params.add(f.profileId());
      }
      params.add(f.formCode());
      inserted +=
          jdbc.sql(
                  """
                  INSERT INTO dossier.form_instance (id, tenant_id, created_by, updated_by,
                      period_id, period_bucket, dossier_package_id, profile_id, form_code,
                      template_version, data_snapshot_hash, status)
                  SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CHO_SINH'
                   WHERE NOT EXISTS (SELECT 1 FROM dossier.form_instance
                                      WHERE period_bucket = ? AND tenant_id = ?
                                        AND dossier_package_id = ?"""
                      + guard
                      + ")")
              .params(params)
              .update();
    }
    return inserted;
  }

  @Override
  public int refresh(
      TenantId tenant, int periodBucket, UUID formId, byte[] hash, String templateVersion) {
    return jdbc.sql(
            "UPDATE dossier.form_instance SET data_snapshot_hash = ?, template_version = ?,"
                + " row_version = row_version + 1, updated_at = now()"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " AND status IN ('CHO_SINH', 'LOI')")
        .params(hash, templateVersion, periodBucket, tenant.value(), formId)
        .update();
  }

  @Override
  public int markRequested(TenantId tenant, int periodBucket, UUID packageId, UUID requestId) {
    return jdbc.sql(
            "UPDATE dossier.form_instance SET render_job_id = ?, row_version = row_version + 1,"
                + " updated_at = now() WHERE period_bucket = ? AND tenant_id = ?"
                + " AND dossier_package_id = ? AND status = 'CHO_SINH' AND render_job_id IS NULL")
        .params(requestId, periodBucket, tenant.value(), packageId)
        .update();
  }

  @Override
  public int markGenerated(
      TenantId tenant,
      int periodBucket,
      UUID formId,
      UUID renderJobId,
      String objectKey,
      byte[] pdfSha256,
      UUID actor) {
    return jdbc.sql(
            "UPDATE dossier.form_instance SET status = 'DA_SINH', render_job_id = ?,"
                + " object_key = ?, pdf_sha256 = ?, row_version = row_version + 1,"
                + " updated_at = now(), updated_by = coalesce(?, updated_by)"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                + " AND status IN ('CHO_SINH', 'LOI')")
        .params(renderJobId, objectKey, pdfSha256, actor, periodBucket, tenant.value(), formId)
        .update();
  }

  @Override
  public int markFailed(
      TenantId tenant, int periodBucket, UUID packageId, UUID requestId, UUID actor) {
    return jdbc.sql(
            "UPDATE dossier.form_instance SET status = 'LOI', row_version = row_version + 1,"
                + " updated_at = now(), updated_by = coalesce(?, updated_by)"
                + " WHERE period_bucket = ? AND tenant_id = ? AND dossier_package_id = ?"
                + " AND status = 'CHO_SINH'"
                + (requestId == null ? "" : " AND render_job_id = ?"))
        .params(
            requestId == null
                ? List.of(nullable(actor), periodBucket, tenant.value(), packageId)
                : List.of(nullable(actor), periodBucket, tenant.value(), packageId, requestId))
        .update();
  }

  @Override
  public int resetFailed(
      TenantId tenant, int periodBucket, UUID packageId, Collection<UUID> formIds, UUID actor) {
    String sql =
        "UPDATE dossier.form_instance SET status = 'CHO_SINH', render_job_id = NULL,"
            + " row_version = row_version + 1, updated_at = now(), updated_by = :actor"
            + " WHERE period_bucket = :bucket AND tenant_id = :tenant AND dossier_package_id = :pkg"
            + " AND status = 'LOI'"
            + (formIds.isEmpty() ? "" : " AND id IN (:ids)");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql)
            .param("actor", actor)
            .param("bucket", periodBucket)
            .param("tenant", tenant.value())
            .param("pkg", packageId);
    if (!formIds.isEmpty()) {
      spec = spec.param("ids", List.copyOf(formIds));
    }
    return spec.update();
  }

  @Override
  public List<UUID> pendingRequests(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            "SELECT DISTINCT render_job_id FROM dossier.form_instance WHERE period_bucket = ?"
                + " AND tenant_id = ? AND dossier_package_id = ? AND status = 'CHO_SINH'"
                + " AND render_job_id IS NOT NULL")
        .params(periodBucket, tenant.value(), packageId)
        .query(UUID.class)
        .list();
  }

  @Override
  public Set<UUID> settledSpecialCases(TenantId tenant, int periodBucket, UUID periodId) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT DISTINCT person_id FROM dossier.special_case WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND period_id = ?"
                    + " AND effect IN ('KHONG_XEP_LOAI', 'XEP_LOAI_TAI_DON_VI_CU', 'BAO_LUU')")
            .params(periodBucket, tenant.value(), periodId)
            .query(UUID.class)
            .list());
  }

  // ---------------------------------------------------------------- ký gói

  @Override
  public String manifest(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            "SELECT manifest::text FROM dossier.dossier_package WHERE period_bucket = ?"
                + " AND tenant_id = ? AND id = ?")
        .params(periodBucket, tenant.value(), packageId)
        .query(String.class)
        .optional()
        .orElse("[]");
  }

  @Override
  public long saveManifest(
      TenantId tenant,
      PackageRow row,
      String manifestJson,
      byte[] manifestHash,
      long expectedVersion,
      UUID actor) {
    return frozen(
        () ->
            jdbc.sql(
                    "UPDATE dossier.dossier_package SET manifest = ?::jsonb, manifest_hash = ?,"
                        + " sign_request_id = NULL, row_version = row_version + 1,"
                        + " updated_at = now(), updated_by = ? WHERE period_bucket = ?"
                        + " AND tenant_id = ? AND id = ? AND status = 'CHO_KY'"
                        + " AND row_version = ? RETURNING row_version")
                .params(
                    manifestJson,
                    manifestHash,
                    actor,
                    row.periodBucket(),
                    tenant.value(),
                    row.id(),
                    (int) expectedVersion)
                .query(Long.class)
                .optional()
                .orElse(0L));
  }

  @Override
  public int attachSignRequest(
      TenantId tenant, int periodBucket, UUID packageId, UUID signRequestId) {
    return frozen(
        () ->
            jdbc.sql(
                    "UPDATE dossier.dossier_package SET sign_request_id = ? WHERE period_bucket = ?"
                        + " AND tenant_id = ? AND id = ? AND status = 'CHO_KY'")
                .params(signRequestId, periodBucket, tenant.value(), packageId)
                .update());
  }

  @Override
  public Optional<PackageRow> findBySignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + " FROM dossier.dossier_package WHERE tenant_id = ? AND sign_request_id = ?"
                + " ORDER BY version_no DESC LIMIT 1")
        .params(tenant.value(), signRequestId)
        .query(this::packageRow)
        .optional();
  }

  @Override
  public long submit(
      TenantId tenant,
      PackageRow row,
      Instant submittedAt,
      byte[] packageSha256,
      String signatureKey,
      UUID actor) {
    return frozen(
        () ->
            jdbc.sql(
                    "UPDATE dossier.dossier_package SET status = 'DA_NOP', submitted_at = ?,"
                        + " package_sha256 = ?, object_key = ?, row_version = row_version + 1,"
                        + " updated_at = now(), updated_by = ? WHERE period_bucket = ?"
                        + " AND tenant_id = ? AND id = ? AND status = 'DA_KY' RETURNING row_version")
                .params(
                    Timestamp.from(submittedAt),
                    packageSha256,
                    nullable(signatureKey),
                    actor,
                    row.periodBucket(),
                    tenant.value(),
                    row.id())
                .query(Long.class)
                .optional()
                .orElse(0L));
  }

  @Override
  public PackageRow newVersion(
      TenantId tenant, PackageRow previous, UUID id, UUID reopenRequestId, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.dossier_package (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, code, org_unit_id, person_id, package_kind, version_no, status,
                deadline, reopen_request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DANG_LAP', ?, ?)
            """)
        .params(
            id,
            tenant.value(),
            actor,
            actor,
            previous.periodId(),
            previous.periodBucket(),
            previous.code(),
            previous.orgUnitId(),
            nullable(previous.personId()),
            previous.kind().name(),
            previous.versionNo() + 1,
            previous.deadline(),
            nullable(reopenRequestId))
        .update();
    return lock(tenant, previous.periodBucket(), id).orElseThrow();
  }

  @Override
  public Optional<UUID> reopenRequest(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            "SELECT reopen_request_id FROM dossier.dossier_package WHERE period_bucket = ?"
                + " AND tenant_id = ? AND id = ? AND reopen_request_id IS NOT NULL")
        .params(periodBucket, tenant.value(), packageId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void insertDraft(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      NewForm form,
      List<UUID> sourceProfiles,
      UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.form_instance (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, profile_id, form_code, template_version, data_snapshot_hash,
                source_profile_ids, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CHO_SINH')
            """)
        .params(
            form.id(),
            tenant.value(),
            actor,
            actor,
            periodId,
            periodBucket,
            form.profileId(),
            form.formCode(),
            form.templateVersion(),
            form.dataSnapshotHash(),
            sourceProfiles == null || sourceProfiles.isEmpty()
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.ARRAY, null)
                : sourceProfiles.toArray(UUID[]::new))
        .update();
  }

  @Override
  public List<UUID> sourceProfiles(TenantId tenant, int periodBucket, UUID formId) {
    return jdbc.sql(
            "SELECT unnest(source_profile_ids) FROM dossier.form_instance WHERE period_bucket = ?"
                + " AND tenant_id = ? AND id = ?")
        .params(periodBucket, tenant.value(), formId)
        .query(UUID.class)
        .list();
  }

  @Override
  public Optional<String> signatureKey(TenantId tenant, int periodBucket, UUID packageId) {
    return jdbc.sql(
            "SELECT object_key FROM dossier.dossier_package WHERE period_bucket = ?"
                + " AND tenant_id = ? AND id = ? AND object_key IS NOT NULL")
        .params(periodBucket, tenant.value(), packageId)
        .query(String.class)
        .optional();
  }

  @Override
  public long archive(TenantId tenant, PackageRow row, String transferredTo, UUID actor) {
    return frozen(
        () ->
            jdbc.sql(
                    "UPDATE dossier.dossier_package SET status = 'DA_LUU_TRU',"
                        + " transferred_to = coalesce(transferred_to, ?),"
                        + " row_version = row_version + 1, updated_at = now(), updated_by = ?"
                        + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                        + " AND status = 'DA_NOP' RETURNING row_version")
                .params(
                    nullable(transferredTo), actor, row.periodBucket(), tenant.value(), row.id())
                .query(Long.class)
                .optional()
                .orElse(0L));
  }

  /**
   * Trigger {@code fn_freeze_submitted_package} chặn mọi thay đổi trên gói đã nộp ({@code
   * object_not_in_prerequisite_state}); lớp mã đổi sang 409 {@code DOSSIER-409-PACKAGE-LOCKED}.
   */
  private static <T> T frozen(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (org.springframework.dao.DataAccessException e) {
      Throwable c = e.getMostSpecificCause();
      if (c instanceof SQLException sql && FROZEN_STATE.equals(sql.getSQLState())) {
        throw new BusinessException(
            "DOSSIER-409-PACKAGE-LOCKED", "Gói hồ sơ đã nộp, không sửa được.");
      }
      throw e;
    }
  }

  // ---------------------------------------------------------------- nội bộ

  private <T> Optional<T> byId(
      String table,
      String columns,
      TenantId tenant,
      UUID id,
      org.springframework.jdbc.core.RowMapper<T> mapper) {
    for (int attempt = 0; attempt < 2; attempt++) {
      Integer[] known = buckets(table, attempt > 0);
      if (known.length == 0) {
        continue;
      }
      Optional<T> found =
          jdbc.sql(
                  "SELECT "
                      + columns
                      + " FROM "
                      + table
                      + " WHERE period_bucket = ANY(?) AND tenant_id = ? AND id = ?")
              .params(known, tenant.value(), id)
              .query(mapper)
              .optional();
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  /** Phân vùng theo kỳ đang có của bảng (giá trị {@code FOR VALUES IN (...)}). */
  private Integer[] buckets(String table, boolean reload) {
    Buckets cached = buckets.get(table);
    Instant now = Instant.now();
    if (!reload && cached != null && cached.loadedAt().plus(BUCKET_TTL).isAfter(now)) {
      return cached.values();
    }
    Set<Integer> values = new java.util.TreeSet<>();
    for (String bound :
        jdbc.sql(
                "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i"
                    + " JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = ?::regclass")
            .param(table)
            .query(String.class)
            .list()) {
      if (bound == null || !bound.contains("IN (")) {
        continue;
      }
      Matcher m = BOUND.matcher(bound.substring(bound.indexOf("IN (")));
      while (m.find()) {
        values.add(Integer.valueOf(m.group()));
      }
    }
    Integer[] out = values.toArray(Integer[]::new);
    buckets.put(table, new Buckets(out, now));
    return out;
  }

  private static Object nullable(UUID v) {
    return v == null
        ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.OTHER, null)
        : v;
  }

  private static Object nullable(String v) {
    return v == null
        ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR, null)
        : v;
  }

  private PackageRow packageRow(ResultSet rs, int i) throws SQLException {
    Timestamp submitted = rs.getTimestamp("submitted_at");
    java.sql.Date deadline = rs.getDate("deadline");
    return new PackageRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("code"),
        rs.getObject("org_unit_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        PackageKind.valueOf(rs.getString("package_kind")),
        rs.getInt("version_no"),
        PackageStatus.valueOf(rs.getString("status")),
        deadline == null ? null : deadline.toLocalDate(),
        submitted == null ? null : submitted.toInstant(),
        rs.getString("transferred_to"),
        rs.getObject("sign_request_id", UUID.class),
        rs.getBytes("manifest_hash"),
        rs.getBytes("package_sha256"),
        rs.getObject("updated_by", UUID.class),
        rs.getLong("row_version"));
  }

  private FormRow formRow(ResultSet rs, int i) throws SQLException {
    Timestamp signed = rs.getTimestamp("signed_at");
    return new FormRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("dossier_package_id", UUID.class),
        rs.getObject("profile_id", UUID.class),
        rs.getString("form_code"),
        rs.getString("template_version"),
        rs.getBytes("data_snapshot_hash"),
        rs.getObject("render_job_id", UUID.class),
        rs.getString("object_key"),
        rs.getBytes("pdf_sha256"),
        FormStatus.valueOf(rs.getString("status")),
        signed == null ? null : signed.toInstant());
  }
}
