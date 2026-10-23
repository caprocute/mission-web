package vn.viettel.okrkpi.integ.infrastructure.hrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.port.PersonnelSink;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Profile;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * DV-13-08: đọc CSV và Excel, ánh xạ trường theo hồ sơ, kiểm từng dòng với số dòng gốc, quy đổi mã
 * qua {@code mapping_xref} và chia lô gửi org-service dưới một lần chạy ổn định.
 */
class HrmTranslatorTest {

  static final UUID T_ID = UUID.randomUUID();
  static final TenantId T = TenantId.of(T_ID);
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
  static final UUID UNIT = UUID.randomUUID();
  static final UUID TITLE = UUID.randomUUID();
  static final UUID PERSON = UUID.randomUUID();
  static final Connector C =
      new Connector(
          UUID.randomUUID(),
          T_ID,
          "HRM_SFTP",
          Protocol.SFTP,
          "sftp://u@h/d",
          null,
          Direction.VAO,
          null,
          true);

  static final Map<String, String> FIELDS =
      Map.of(
          "externalId", "Mã cán bộ",
          "full-name", "Họ và tên",
          "nationalId", "Số định danh",
          "birthDate", "Ngày sinh",
          "orgUnit", "Mã đơn vị",
          "jobTitle", "Mã chức danh",
          "partyMember", "Đảng viên",
          "employmentStatus", "Trạng thái");

  record Sent(UUID run, List<PersonnelSink.Person> persons, boolean last) {}

  final List<Sent> sent = new ArrayList<>();
  final PersonnelSink sink =
      new PersonnelSink() {
        @Override
        public void forward(
            vn.viettel.okrkpi.shared.kernel.TenantId tenant,
            String code,
            UUID run,
            List<Person> persons,
            boolean last) {
          sent.add(new Sent(run, List.copyOf(persons), last));
        }

        @Override
        public void close(
            vn.viettel.okrkpi.shared.kernel.TenantId tenant,
            String code,
            UUID run,
            int deadLetters,
            String reason) {
          sent.add(new Sent(run, List.of(), true));
        }
      };
  final MappingXrefService mappings = mock(MappingXrefService.class);
  final TranslationContext ctx = new TranslationContext(T, C, TODAY, mappings);

  HrmTranslatorTest() {
    when(mappings.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    when(mappings.resolve(T, C.id(), EntityType.DON_VI, "DV-01", TODAY))
        .thenReturn(Optional.of(UNIT));
    when(mappings.resolve(T, C.id(), EntityType.CHUC_DANH, "CV", TODAY))
        .thenReturn(Optional.of(TITLE));
    when(mappings.resolve(T, C.id(), EntityType.NHAN_SU, "NS-1", TODAY))
        .thenReturn(Optional.of(PERSON));
  }

  static Profile profile(Integer batchSize) {
    return new Profile(
        null,
        null,
        null,
        FIELDS,
        Map.of("employment_status", Map.of("Đang làm việc", "DANG_CONG_TAC")),
        List.of("externalId", "fullName", "birthDate"),
        null,
        null,
        batchSize,
        null);
  }

  HrmPersonTranslator translator(Integer batchSize) {
    return new HrmPersonTranslator("HRM_SFTP", profile(batchSize), sink, new ObjectMapper());
  }

  static InboundMessage csv(String text) {
    return new InboundMessage("tep:1", BatchFiles.TYPE_CSV, text.getBytes(StandardCharsets.UTF_8));
  }

  static final String HEADER =
      "﻿Mã cán bộ;Họ và tên;Số định danh;Ngày sinh;Mã đơn vị;Mã chức danh;Đảng viên;Trạng thái\n";

  @Test
  void tepCsvDungChuyenCaLoVoiMaNoiBo() {
    translator(null)
        .translate(
            ctx,
            csv(
                HEADER
                    + "NS-1;\"Nguyễn Văn; A\";001099000001;1980-05-02;DV-01;CV;Có;Đang làm việc\r\n"
                    + ";;;;;;;\n"
                    + "NS-2;Trần Thị B;;02/03/1985;;;0;\n"));
    assertThat(sent).hasSize(1);
    List<PersonnelSink.Person> p = sent.get(0).persons();
    assertThat(p).hasSize(2);
    assertThat(p.get(0).fullName()).isEqualTo("Nguyễn Văn; A");
    assertThat(p.get(0).mappedPersonId()).isEqualTo(PERSON);
    assertThat(p.get(0).birthDate()).isEqualTo(LocalDate.of(1980, 5, 2));
    assertThat(p.get(0).partyMember()).isTrue();
    assertThat(p.get(0).employmentStatus()).isEqualTo("DANG_CONG_TAC");
    assertThat(p.get(0).posts())
        .singleElement()
        .satisfies(
            post -> {
              assertThat(post.orgUnitId()).isEqualTo(UNIT);
              assertThat(post.jobTitleId()).isEqualTo(TITLE);
              assertThat(post.kind()).isEqualTo("CHINH");
            });
    assertThat(p.get(1).birthDate()).isEqualTo(LocalDate.of(1985, 3, 2));
    assertThat(p.get(1).posts()).isEmpty();
    assertThat(p.get(1).employmentStatus()).isEqualTo("DANG_CONG_TAC");
    assertThat(sent.get(0).run()).isEqualTo(HrmPersonTranslator.runId(ctx, csv("x")));
  }

  @Test
  void thieuCotBatBuocLaSaiCauTruc() {
    assertThatThrownBy(() -> translator(null).translate(ctx, csv("Mã cán bộ,Họ và tên\nNS-1,A\n")))
        .isInstanceOfSatisfying(
            TranslationRejectedException.class,
            e -> {
              assertThat(e.reasonCode()).isEqualTo(HrmPersonTranslator.BAD_STRUCTURE);
              assertThat(e.getMessage()).contains("Ngày sinh");
            });
    assertThatThrownBy(() -> translator(null).translate(ctx, csv("")))
        .isInstanceOf(TranslationRejectedException.class);
    assertThatThrownBy(() -> translator(null).translate(ctx, csv("Mã cán bộ,Mã cán bộ\n")))
        .hasMessageContaining("trùng");
    assertThatThrownBy(
            () ->
                translator(null)
                    .translate(
                        ctx,
                        new InboundMessage(
                            "tep:2", BatchFiles.TYPE_CSV, new byte[] {(byte) 0xC3, 0x28})))
        .hasMessageContaining("UTF-8");
    assertThatThrownBy(() -> translator(null).translate(ctx, csv(HEADER + "NS-1;\"chưa đóng\n")))
        .hasMessageContaining("ngoặc kép");
    assertThatThrownBy(
            () ->
                translator(null)
                    .translate(
                        ctx, new InboundMessage("q", BatchFiles.TYPE_OVERSIZE, new byte[] {1})))
        .hasMessageContaining("dung lượng");
    assertThatThrownBy(
            () -> translator(null).translate(ctx, new InboundMessage("q", "KHAC", new byte[] {1})))
        .hasMessageContaining("không hỗ trợ");
    assertThat(sent).isEmpty();
  }

  @Test
  void dongLoiBaoSoDongVaNhanTruongKhongInGiaTri() {
    assertThatThrownBy(
            () ->
                translator(null)
                    .translate(
                        ctx,
                        csv(
                            HEADER
                                + "NS-1;A;001099000001;31/02/1980;DV-01;CV;;\n"
                                + "NS-1;B;;1980-01-01;;;có lẽ;Đang nghỉ\n"
                                + ";C;;1980-01-01;DV-01;;;;thừa\n")))
        .isInstanceOfSatisfying(
            TranslationRejectedException.class,
            e -> {
              assertThat(e.reasonCode()).isEqualTo(HrmPersonTranslator.BAD_ROWS);
              assertThat(e.getMessage())
                  .contains("Có 3 dòng lỗi")
                  .contains("dòng 2 (ngày sinh không hợp lệ)")
                  .contains(
                      "dòng 3 (trạng thái công tác không hợp lệ, đảng viên không hợp lệ, trùng mã cán bộ)")
                  .contains("dòng 4 (thừa cột, thiếu mã cán bộ, thiếu chức danh)")
                  .doesNotContain("001099000001", "31/02");
            });
    assertThat(sent).isEmpty();
  }

  @Test
  void maDonViHoacChucDanhChuaAnhXaVaoThuChet() {
    assertThatThrownBy(
            () -> translator(null).translate(ctx, csv(HEADER + "NS-9;A;;1980-01-01;DV-99;CV;;\n")))
        .isInstanceOfSatisfying(
            TranslationRejectedException.class,
            e -> {
              assertThat(e.reasonCode()).isEqualTo(HrmPersonTranslator.UNMAPPED);
              assertThat(e.getMessage()).contains("dòng 2 (đơn vị DV-99)");
            });
  }

  @Test
  void excelDocNgayKieuSoVaChiaLo() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Workbook wb = new Workbook(out, "test", "1.0")) {
      Worksheet ws = wb.newWorksheet("Nhân sự");
      String[] h = {
        "Mã cán bộ", "Họ và tên", "Ngày sinh", "Mã đơn vị", "Mã chức danh", "Đảng viên"
      };
      for (int c = 0; c < h.length; c++) {
        ws.value(0, c, h[c]);
      }
      for (int r = 1; r <= 3; r++) {
        ws.value(r, 0, "NS-X" + r);
        ws.value(r, 1, "Người " + r);
        ws.value(r, 2, 29343); // 1980-05-02 dạng số ngày Excel
        ws.value(r, 3, "DV-01");
        ws.value(r, 4, "CV");
        ws.value(r, 5, r == 1);
      }
    }
    translator(2)
        .translate(ctx, new InboundMessage("tep:x", BatchFiles.TYPE_XLSX, out.toByteArray()));
    assertThat(sent).hasSize(2);
    assertThat(sent.get(0).persons()).hasSize(2);
    assertThat(sent.get(1).persons()).hasSize(1);
    assertThat(sent.get(0).run()).isEqualTo(sent.get(1).run());
    assertThat(sent).extracting(Sent::last).containsExactly(false, true);
    assertThat(sent.get(0).persons().get(0).birthDate()).isEqualTo(LocalDate.of(1980, 5, 2));
    assertThat(sent.get(0).persons().get(0).partyMember()).isTrue();
    assertThat(sent.get(0).persons().get(1).partyMember()).isFalse();

    assertThatThrownBy(
            () ->
                translator(null)
                    .translate(
                        ctx, new InboundMessage("tep:y", BatchFiles.TYPE_XLSX, new byte[] {1, 2})))
        .hasMessageContaining("Excel");
  }

  @Test
  void trangJsonTheoConTroTruong() {
    Profile p =
        new Profile(
            null,
            null,
            null,
            Map.of(
                "externalId",
                "/ma",
                "fullName",
                "hoTen",
                "orgUnit",
                "/donVi/ma",
                "jobTitle",
                "/cd"),
            Map.of(),
            null,
            null,
            null,
            null,
            null);
    HrmPersonTranslator t = new HrmPersonTranslator("HRM", p, sink, new ObjectMapper());
    t.translate(
        ctx,
        new InboundMessage(
            "trang:1",
            BatchFiles.TYPE_JSON,
            "[{\"ma\":\"NS-1\",\"hoTen\":\"A\",\"donVi\":{\"ma\":\"DV-01\"},\"cd\":\"CV\",\"head\":true,\"kind\":\"kiem_nhiem\"},{\"ma\":\"NS-2\",\"hoTen\":\"B\",\"birthDate\":null}]"
                .getBytes(StandardCharsets.UTF_8)));
    assertThat(sent.get(0).persons()).hasSize(2);
    assertThat(sent.get(0).persons().get(0).posts().get(0).head()).isTrue();
    assertThat(sent.get(0).persons().get(0).posts().get(0).kind()).isEqualTo("KIEM_NHIEM");
    assertThatThrownBy(
            () ->
                t.translate(
                    ctx, new InboundMessage("trang:2", BatchFiles.TYPE_JSON, "{}".getBytes())))
        .hasMessageContaining("danh sách");
    assertThatThrownBy(
            () ->
                t.translate(
                    ctx, new InboundMessage("trang:3", BatchFiles.TYPE_JSON, "{x".getBytes())))
        .hasMessageContaining("JSON");
    // Trang rỗng không gửi gì.
    t.translate(ctx, new InboundMessage("trang:4", BatchFiles.TYPE_JSON, "[]".getBytes()));
    assertThat(sent).hasSize(1);
  }

  @Test
  void moTaLoiCatODoDai() {
    Map<Integer, List<String>> errors = new java.util.LinkedHashMap<>();
    for (int i = 2; i < 200; i++) {
      errors.put(i, List.of("thiếu họ tên"));
    }
    String d = PersonRows.describe(errors, 100);
    assertThat(d.length()).isLessThanOrEqualTo(104);
    assertThat(d).startsWith("dòng 2 (thiếu họ tên); dòng 3").endsWith("…");
    assertThat(BatchFiles.column("  Mã Cán Bộ ")).isEqualTo("mã cán bộ");
    assertThat(SftpBatchConnector.key(5, "a.csv")).isEqualTo("000000000005|a.csv");
  }

  @Test
  void trongLanDongBoThiLoThuocLanChayVaKhongDong() {
    UUID run = UUID.randomUUID();
    new HrmPersonTranslator(
            "HRM_SFTP", profile(2), sink, new ObjectMapper(), (t, c) -> Optional.of(run))
        .translate(ctx, csv(HEADER + "NS-9;Lê Văn D;;1981-01-01;DV-01;CV;0;\n"));
    assertThat(sent)
        .isNotEmpty()
        .allSatisfy(
            x -> {
              assertThat(x.run()).isEqualTo(run);
              assertThat(x.last()).isFalse();
            });
  }
}
