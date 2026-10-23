package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput.WorkItem;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;

/** Dựng đầu vào chuẩn của hồ sơ từ dữ liệu lõi theo khai báo {@code inputs} của gói (DV-07-04). */
class InputAssemblerTest {

  static final UUID PROFILE = UUID.randomUUID();
  static final UUID PERSON = UUID.randomUUID();
  private static FlowableDecisionTables tables;
  private static CompiledPolicy policy;
  private final InputAssembler assembler = new InputAssembler();

  @BeforeAll
  static void compile() {
    tables = new FlowableDecisionTables();
    policy =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
            .compile(SamplePolicyPack.content());
  }

  @AfterAll
  static void stop() {
    tables.destroy();
  }

  static WorkItem item(
      String group, String status, String quality, int subjLate, boolean deferred) {
    return new WorkItem(
        UUID.randomUUID(),
        null,
        new BigDecimal("120"),
        3,
        group,
        group == null ? null : "TR1",
        status,
        quality == null ? null : new BigDecimal(quality),
        subjLate,
        1,
        true,
        false,
        deferred);
  }

  static CoreScoringInput input(List<WorkItem> items, int incidents, boolean criteriaMet) {
    return new CoreScoringInput(
        PROFILE,
        PERSON,
        "DANG",
        UUID.randomUUID(),
        null,
        true,
        false,
        UUID.randomUUID(),
        List.of(
            new CoreScoringInput.Criterion("TCC-01", true),
            new CoreScoringInput.Criterion("TCC-02", criteriaMet)),
        items,
        incidents);
  }

  @Test
  void anhXaThuocTinhTheoNguonVaMacDinhChoNguonChuaCo() {
    InputAssembler.Assembled a =
        assembler.assemble(
            policy,
            input(
                List.of(
                    item("THEN_CHOT", "HOAN_THANH", "0.75", 0, false),
                    item("THEN_CHOT", "DANG_THUC_HIEN", null, 2, false),
                    item(null, "NHAP", null, 0, false),
                    item("THUONG_XUYEN", "HOAN_THANH", "1", 0, true)),
                1,
                false));
    ScoringInput in = a.input();
    assertThat(in.attributes())
        .containsEntry("la_nguoi_dung_dau", true)
        .containsEntry("giu_chuc_vu", false)
        .containsEntry("loai_tru", true)
        .containsEntry("ty_le_dap_ung_tieu_chi_chung", 0.5)
        .containsEntry("ty_le_O", 0.0)
        .containsEntry("giai_phap_dot_pha_da_kiem_chung", false)
        .containsEntry("xep_loai_nha_nuoc", "");
    assertThat(a.defaulted()).contains("ty_le_O").doesNotContain("la_nguoi_dung_dau");
    // Việc nháp và việc lùi theo quyết định không được tính.
    assertThat(in.tasks()).hasSize(2);
    TaskInput done = in.tasks().get(0);
    assertThat(done.catalogBaseScore()).isEqualByComparingTo("100");
    assertThat(done.toNhiemVu())
        .containsEntry("nhom", "THEN_CHOT")
        .containsEntry("ma", "TR1")
        .containsEntry("so_luong_duyet", 1L)
        .containsEntry("muc_chat_luong", 0.75)
        .containsEntry("khong_hoan_thanh_chu_quan", false);
    assertThat(in.tasks().get(1).toNhiemVu())
        .containsEntry("so_luong_duyet", 0L)
        .containsEntry("ngay_lam_viec_cham_chu_quan", 2L)
        .containsEntry("khong_hoan_thanh_chu_quan", true);
  }

  @Test
  void nguonPhaiSinhTuViecLa() {
    CoreScoringInput in =
        input(
            List.of(
                item("THEN_CHOT", "DANG_THUC_HIEN", null, 3, false),
                item("THUONG_XUYEN", "HOAN_THANH", "0.5", 0, false)),
            0,
            true);
    Map<String, Object> decl =
        Map.of(
            "co",
            Map.of("type", "bool", "source", "appraisal.aggregate.has_key_work"),
            "du",
            Map.of("type", "bool", "source", "appraisal.aggregate.all_key_work_completed"),
            "truot",
            Map.of("type", "bool", "source", "appraisal.aggregate.key_missed_subjective"),
            "vuot",
            Map.of("type", "double", "source", "appraisal.aggregate.exceeded_ratio"),
            "doi_tuong",
            Map.of(
                "type", "string",
                "source", "appraisal.aggregate.subject_group",
                "enum", List.of("LANH_DAO", "KHONG_GIU_CHUC_VU")),
            "cap",
            Map.of("type", "string", "enum", List.of("TINH", "HUYEN")),
            "so",
            Map.of("type", "int", "default", 7),
            "muc",
            Map.of("type", "list<MucPhuTrach>"));
    InputAssembler.Assembled a = assembler.assemble(policyWithInputs(decl), in);
    assertThat(a.input().attributes())
        .containsEntry("co", true)
        .containsEntry("du", false)
        .containsEntry("truot", true)
        .containsEntry("vuot", 1.0)
        .containsEntry("doi_tuong", "KHONG_GIU_CHUC_VU")
        .containsEntry("cap", "TINH")
        .containsEntry("so", 7L)
        .doesNotContainKey("muc");
    assertThat(a.defaulted())
        .contains("cap", "so")
        .doesNotContain("co", "du", "truot", "vuot", "doi_tuong");

    CoreScoringInput empty = input(List.of(), 0, true);
    assertThat(assembler.assemble(policyWithInputs(decl), empty).input().attributes())
        .containsEntry("vuot", 0.0)
        .containsEntry("du", true);
  }

  @Test
  void mucChatLuongNgoaiNamMucLaLoiDauVao() {
    CoreScoringInput in = input(List.of(item("THEN_CHOT", "HOAN_THANH", "0.6", 0, false)), 0, true);
    assertThatThrownBy(() -> assembler.assemble(policy, in))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CompiledPolicy policyWithInputs(Map<String, Object> inputs) {
    var content = SamplePolicyPack.content();
    var manifest = new java.util.LinkedHashMap<>(content.manifest());
    // Giữ đầu vào gốc để luật của gói vẫn kiểm được kiểu, chỉ bổ sung khai báo cần thử.
    @SuppressWarnings("unchecked")
    var merged =
        new java.util.LinkedHashMap<>((Map<String, Object>) content.manifest().get("inputs"));
    merged.putAll(inputs);
    manifest.put("inputs", merged);
    return new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
        .compile(
            new vn.viettel.okrkpi.scoring.domain.PolicyContent(
                content.pinned(), manifest, content.rules(), content.tables()));
  }
}
