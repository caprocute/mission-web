package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.SavedTable;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.TableEdit;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService.TableView;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Shadow;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu bảng quyết định DMN (CORE-05-05): kiểm dữ liệu (BR-15-06), FEEL ({@code POLICY-422-FEEL}),
 * biến mẫu câu ({@code POLICY-422-TEMPLATE-VAR}, MSG-15-05), dòng bị che (MSG-15-04), trạng thái
 * phiên bản, gói kế thừa và khoá lạc quan.
 */
class PolicyDecisionTableServiceTest {

  private static final String MANIFEST =
      """
      {"inputs":{"ty_le_dap_ung":{"type":"double"}},"inputs_nam":{"cac_quy_dat":{"type":"int"}},
       "ngu_canh":{"nv":{"type":"NhiemVu"}},
       "definitions":{"ho_so":{"diem_ket_qua":{"result_type":"DECIMAL"}},"x":"khong_phai_map"}}
      """;

  /** Kho bảng trong bộ nhớ theo phiên bản và khoá bảng. */
  private static final class Tables implements PolicyDecisionTableRepository {
    final Map<String, StoredTable> rows = new LinkedHashMap<>();
    boolean rejectReplace;

    @Override
    public List<StoredTable> storedTables(TenantId tenant, UUID versionId) {
      return rows.values().stream().filter(s -> s.table().versionId().equals(versionId)).toList();
    }

    @Override
    public Optional<StoredTable> storedTable(TenantId tenant, UUID versionId, String tableKey) {
      return Optional.ofNullable(rows.get(versionId + "/" + tableKey));
    }

    @Override
    public boolean replaceTable(TenantId tenant, DecisionTable table, long expected, UUID actor) {
      if (rejectReplace) {
        return false;
      }
      rows.put(table.versionId() + "/" + table.tableKey(), new StoredTable(table, expected + 1));
      return true;
    }

    @Override
    public void appendTable(TenantId tenant, DecisionTable table, UUID actor) {
      rows.put(table.versionId() + "/" + table.tableKey(), new StoredTable(table, 0));
    }
  }

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final Tables tables = new Tables();
  private final PolicyAudit audit = mock(PolicyAudit.class);
  private final PolicyDecisionTableService service =
      new PolicyDecisionTableService(packs, tables, audit, new ObjectMapper());

  private PolicyPack pack() {
    PolicyPack p =
        new PolicyPack(
            UUID.randomUUID(),
            "khoi-dang",
            "Gói",
            "KHOI",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, p.id())).thenReturn(Optional.of(p));
    return p;
  }

  private PolicyPackVersion version(PolicyPack p, PolicyVersionStatus s, UUID source) {
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            p.id(),
            "1.0.0",
            s,
            null,
            MANIFEST,
            null,
            s == PolicyVersionStatus.NHAP ? null : BigDecimal.ONE,
            null,
            false,
            null,
            null,
            null,
            null,
            source,
            "x",
            null,
            null,
            null,
            null,
            0);
    when(packs.findVersion(tenant, p.id(), "1.0.0")).thenReturn(Optional.of(v));
    return v;
  }

  private static final List<Column> COLUMNS =
      List.of(
          new Column("diem", "Điểm", "number"),
          new Column("ty_le_vuot", null, "number"),
          new Column("truot", null, "boolean"));

  private static Row row(String diem, String vuot, String truot, String level) {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put("diem", diem);
    cells.put("ty_le_vuot", vuot);
    cells.put("truot", truot);
    return new Row(cells, level, "QĐ 366 Điều 11", "Đạt {diem} điểm");
  }

  private static TableEdit xepLoai(Map<String, String> templates) {
    return new TableEdit(
        "FIRST",
        " QĐ 366 ",
        COLUMNS,
        List.of(
            row(">= 90", ">= 0.30", "-", "HTXSNV"),
            row(">= 80", "-", "false", "HTTNV"),
            row(">= 60", "-", "-", "HTNV"),
            row("-", "-", "-", "KHTNV")),
        templates);
  }

  private SavedTable save(PolicyPack p, long expected, TableEdit e) {
    return service.storeTable(tenant, actor, p.id(), "1.0.0", "xep_loai", expected, e);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).getMessage();
  }

  @Test
  void tableIsAppendedReplacedAndReadBack() {
    PolicyPack p = pack();
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP, null);
    Map<String, String> templates =
        Map.of(
            "HTTNV", "Đạt {diem}, thiếu {dieu_kien_thieu}, {ty_le_dap_ung}, {cac_quy_dat}",
            "HTNV", "{nv} {diem_ket_qua}");
    SavedTable created = save(p, 0, xepLoai(templates));
    assertThat(created.shadowed()).isEmpty();
    assertThat(created.view().stored().rowVersion()).isZero();
    DecisionTable t = created.view().stored().table();
    assertThat(t.legalRef()).isEqualTo("QĐ 366");
    assertThat(t.dmnHash()).hasSize(32).isEqualTo(PolicyDecisionTableService.sha256(t.dmnXml()));
    assertThat(t.rulesJson()).startsWith("{\"inputs\":[");

    SavedTable replaced = save(p, 0, xepLoai(null));
    assertThat(replaced.view().stored().rowVersion()).isEqualTo(1);
    assertThat(replaced.view().stored().table().id()).isEqualTo(t.id());
    verify(audit, times(2))
        .changed(
            eq(tenant),
            eq(actor),
            eq("LUU_BANG_QUYET_DINH_POLICY_PACK"),
            eq("decision_table"),
            eq("khoi-dang@1.0.0/xep_loai"),
            eq(t.id()),
            anyLong());

    List<TableView> list = service.listTables(tenant, p.id(), "1.0.0");
    assertThat(list).hasSize(1);
    TableView view = service.findTable(tenant, p.id(), "1.0.0", "xep_loai").orElseThrow();
    assertThat(view.spec().columns()).isEqualTo(COLUMNS);
    assertThat(view.spec().rows()).hasSize(4);
    assertThat(view.spec().evaluate(Map.of("diem", 85, "ty_le_vuot", 0, "truot", false)))
        .contains("HTTNV");
    assertThat(view.explanationTemplates()).isEmpty();
    assertThat(service.findTable(tenant, p.id(), "1.0.0", "khac")).isEmpty();
    assertThat(v.status()).isEqualTo(PolicyVersionStatus.NHAP);
  }

  @Test
  void legacyArrayRulesAreStillRead() {
    PolicyPack p = pack();
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP, null);
    tables.appendTable(
        tenant,
        new DecisionTable(
            UUID.randomUUID(),
            v.id(),
            "cu",
            "<definitions/>",
            "FIRST",
            new byte[32],
            "",
            "[{\"inputs\":{\"diem\":\">= 50\"},\"then\":\"HTNV\"}]",
            "{\"HTNV\":\"Đạt\"}"),
        actor);
    TableView view = service.findTable(tenant, p.id(), "1.0.0", "cu").orElseThrow();
    assertThat(view.spec().columns()).isEmpty();
    assertThat(view.spec().rows().get(0).ratingLevel()).isEqualTo("HTNV");
    assertThat(view.spec().rows().get(0).clause()).isEmpty();
    assertThat(view.explanationTemplates()).containsEntry("HTNV", "Đạt");
  }

  @Test
  void shadowedRowIsWarnedWithMsg1504() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.NHAP, null);
    SavedTable saved =
        save(
            p,
            0,
            new TableEdit(
                "FIRST",
                null,
                COLUMNS,
                List.of(row(">= 60", "-", "-", "HTNV"), row(">= 80", "-", "-", "HTTNV")),
                null));
    assertThat(saved.shadowed()).containsExactly(new Shadow(2, 1));
    assertThat(PolicyDecisionTableService.shadowMessage(saved.shadowed().get(0)))
        .isEqualTo(
            "Dòng 2 không bao giờ được chọn vì dòng 1 phía trên luôn thoả mãn trước"
                + " (hit policy FIRST).");
  }

  @Test
  void invalidContentIsRejectedWithFieldErrors() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.NHAP, null);
    Map<String, String> tooLong = new HashMap<>();
    tooLong.put("HTNV", "x".repeat(1001));
    Map<String, String> cells = Map.of("khong_khai", "1");
    TableEdit bad =
        new TableEdit(
            "COLLECT",
            "x".repeat(201),
            List.of(new Column("diem", null, "date"), new Column("diem", null, "number")),
            List.of(new Row(cells, "Xuất sắc", " ", "y".repeat(201))),
            tooLong);
    assertThatThrownBy(() -> save(p, 0, bad))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            t -> {
              BusinessException b = (BusinessException) t;
              assertThat(b.getMessage()).isEqualTo("COMMON-422-VALIDATION");
              assertThat(b.errors())
                  .extracting(e -> e.field())
                  .contains(
                      "hitPolicy",
                      "legalRef",
                      "inputs[0].type",
                      "inputs[1].name",
                      "rules[0].ratingLevel",
                      "rules[0].clause",
                      "rules[0].explanation",
                      "rules[0].inputs.khong_khai",
                      "explanationTemplates.HTNV");
            });
    assertThatThrownBy(() -> save(p, 0, new TableEdit(null, null, null, null, null)))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errors())
                    .extracting(e -> e.field())
                    .contains("hitPolicy", "inputs", "rules"));
  }

  @Test
  void tableWithGeneralOutputIsStoredAndMixedOutputsRejected() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.NHAP, null);
    List<Column> cols = List.of(new Column("ty_le", null, "number"));
    TableEdit tieuChi =
        new TableEdit(
            "FIRST",
            "QĐ Điều 10",
            cols,
            List.of(
                new Row(Map.of("ty_le", ">= 0.9"), "THUC_HIEN_TOT", "Điều 10", "Từ 90%"),
                new Row(Map.of(), "KHONG_DAM_BAO", "Điều 10", "Còn lại")),
            null);
    assertThat(save(p, 0, tieuChi).view().spec().rows())
        .extracting(Row::ratingLevel)
        .containsExactly("THUC_HIEN_TOT", "KHONG_DAM_BAO");

    TableEdit mixed =
        new TableEdit(
            "FIRST",
            "QĐ Điều 10",
            cols,
            List.of(
                new Row(Map.of("ty_le", ">= 0.9"), "HTXSNV", "Điều 10", "Từ 90%"),
                new Row(Map.of(), "KHONG_DAM_BAO", "Điều 10", "Còn lại")),
            null);
    assertThatThrownBy(() -> save(p, 0, mixed))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errors())
                    .extracting(e -> e.field())
                    .contains("rules"));
  }

  @Test
  void badFeelAndUnknownTemplateVariableAre422() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.NHAP, null);
    TableEdit feel =
        new TableEdit("FIRST", null, COLUMNS, List.of(row(">= 9O", "-", "-", "HTNV")), null);
    assertThatThrownBy(() -> save(p, 0, feel))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo("POLICY-422-FEEL");
              assertThat(((BusinessException) t).detail())
                  .startsWith("Biểu thức không hợp lệ ở dòng 1, cột diem");
              assertThat(((BusinessException) t).errors().get(0).field())
                  .isEqualTo("rules[0].inputs.diem");
            });
    assertThatThrownBy(() -> save(p, 0, xepLoai(Map.of("HTNV", "Điểm {diem_ca_nhan}"))))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo("POLICY-422-TEMPLATE-VAR");
              assertThat(((BusinessException) t).detail()).contains("diem_ca_nhan");
            });
  }

  @Test
  void onlyDraftOwnTablesWithMatchingVersionAreSaved() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.DAT_KIEM_THU, null);
    assertThatThrownBy(() -> save(p, 0, xepLoai(null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-VERSION-FROZEN"));

    PolicyPack child = pack();
    UUID parent = UUID.randomUUID();
    PolicyPackVersion v = version(child, PolicyVersionStatus.NHAP, parent);
    tables.appendTable(
        tenant,
        new DecisionTable(
            UUID.randomUUID(), parent, "xep_loai", "<x/>", "FIRST", new byte[32], "", "{}", "{}"),
        actor);
    assertThatThrownBy(() -> save(child, 0, xepLoai(null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-403-NOT-OVERRIDABLE"));

    SavedTable own =
        service.storeTable(tenant, actor, child.id(), "1.0.0", "rieng", 0, xepLoai(null));
    assertThat(own.view().stored().table().versionId()).isEqualTo(v.id());
    assertThatThrownBy(
            () -> service.storeTable(tenant, actor, child.id(), "1.0.0", "rieng", 5, xepLoai(null)))
        .isInstanceOf(VersionConflictException.class);
    tables.rejectReplace = true;
    assertThatThrownBy(
            () -> service.storeTable(tenant, actor, child.id(), "1.0.0", "rieng", 0, xepLoai(null)))
        .isInstanceOf(VersionConflictException.class);

    UUID missing = UUID.randomUUID();
    when(packs.findPack(tenant, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listTables(tenant, missing, "1.0.0"))
        .isInstanceOf(NoSuchElementException.class);
    when(packs.findVersion(any(), eq(p.id()), eq("9.9.9"))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listTables(tenant, p.id(), "9.9.9"))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(new ArrayList<>(tables.rows.keySet())).hasSize(2);
  }
}
