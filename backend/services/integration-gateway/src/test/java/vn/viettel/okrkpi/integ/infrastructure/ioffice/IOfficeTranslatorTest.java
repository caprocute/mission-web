package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.integ.infrastructure.voffice.DirectiveEvents;
import vn.viettel.okrkpi.integ.infrastructure.voffice.VOfficeTranslator;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Văn bản iOffice cho EVT-24 cùng lược đồ Avro và cùng giá trị với văn bản vOffice tương đương
 * (tiêu chí DV-13-04); ánh xạ trường, chọn nơi nhận, văn bản mật và trường thiếu.
 */
class IOfficeTranslatorTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID DOC = UUID.randomUUID();
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

  final MappingXrefService mappings = mock(MappingXrefService.class);
  final ObjectMapper json = new ObjectMapper();
  final IOfficeTranslator translator = new IOfficeTranslator(json);

  IOfficeTranslatorTest() {
    when(mappings.resolve(any(), any(), eq(EntityType.DON_VI), eq("DV-01"), any()))
        .thenReturn(Optional.of(UUID.randomUUID()));
    when(mappings.resolve(any(), any(), eq(EntityType.DON_VI), eq("DV-99"), any()))
        .thenReturn(Optional.empty());
    when(mappings.resolveOrCreate(any(), any(), eq(EntityType.VAN_BAN), any(), any(), any()))
        .thenReturn(DOC);
  }

  static Connector connector(String code) {
    return new Connector(
        UUID.randomUUID(),
        T.value(),
        code,
        Protocol.REST,
        "https://gia-lap.invalid",
        null,
        Direction.VAO,
        null,
        true);
  }

  TranslationContext ctx(String code) {
    return new TranslationContext(T, connector(code), TODAY, mappings);
  }

  static InboundMessage msg(String id, String type, String body) {
    return new InboundMessage(id, type, body.getBytes(StandardCharsets.UTF_8));
  }

  static InboundMessage ioffice(String body) {
    return msg("VB-2026-0001:2", IOfficeConnector.TYPE_JSON, body);
  }

  static final String IOFFICE =
      """
      {"vanBanId":"VB-2026-0001","phienBan":2,"soKyHieu":"12/CT-UBND","ngayBanHanh":"19/10/2026",
       "coQuanBanHanh":"Uỷ ban nhân dân tỉnh","trichYeu":"Chỉ đạo triển khai nhiệm vụ quý IV",
       "doKhan":"KHAN","doMat":"THUONG","nguoiKy":"Nguyễn Văn An",
       "noiNhan":[{"maDonVi":"DV-99","tenDonVi":"Chưa ánh xạ"},{"maDonVi":"DV-01","tenDonVi":"Ban"}],
       "tepDinhKem":[{"maTep":"F-1","tenTep":"12-CT.pdf"}],"thoiDiemCapNhat":"2026-10-20T08:00:00+07:00"}
      """;

  @Test
  void vanBanIOfficeCungLuocDoVaGiaTriVoiVanBanVOfficeTuongDuong() {
    List<CanonicalEvent> fromIOffice = translator.translate(ctx("IOFFICE"), ioffice(IOFFICE));
    List<CanonicalEvent> fromVOffice =
        new VOfficeTranslator(json)
            .translate(
                ctx("VOFFICE"),
                msg(
                    "VB-2026-0001:2",
                    "VOFFICE_JSON",
                    """
                    {"id":"VB-2026-0001","version":2,"docNumber":"12/CT-UBND",
                     "issuedDate":"2026-10-19","issuer":"Uỷ ban nhân dân tỉnh",
                     "summary":"Chỉ đạo triển khai nhiệm vụ quý IV","recipientUnitId":"DV-01"}
                    """));
    assertThat(fromIOffice).hasSize(1);
    CanonicalEvent i = fromIOffice.get(0);
    CanonicalEvent v = fromVOffice.get(0);
    assertThat(i.topic()).isEqualTo(v.topic()).isEqualTo(DirectiveEvents.TOPIC);
    assertThat(i.aggregateType()).isEqualTo(v.aggregateType());
    assertThat(i.aggregateId()).isEqualTo(DOC);
    assertThat(i.aggregateVersion()).isEqualTo(v.aggregateVersion()).isEqualTo(2);
    assertThat(i.classification()).isEqualTo(v.classification());
    assertThat(i.payload().getSchema()).isEqualTo(v.payload().getSchema());

    VanBanChiDaoDaNhan p = (VanBanChiDaoDaNhan) i.payload();
    assertThat(p.getConnectorCode()).isEqualTo("IOFFICE");
    assertThat(p.getIssuedDate()).isEqualTo(LocalDate.of(2026, 10, 19));
    VanBanChiDaoDaNhan sameSource =
        VanBanChiDaoDaNhan.newBuilder(p).setConnectorCode("VOFFICE").build();
    assertThat(sameSource).as("chỉ khác mã kết nối nguồn").isEqualTo(v.payload());
  }

  @Test
  void ngayDangIsoVaNoiNhanDauTienKhiChuaAnhXa() {
    assertThat(IOfficeTranslator.date("2026-10-19")).isEqualTo(LocalDate.of(2026, 10, 19));
    assertThatThrownBy(() -> IOfficeTranslator.date("19-10-2026"))
        .isInstanceOf(IllegalArgumentException.class);
    String unmapped = IOFFICE.replace("\"maDonVi\":\"DV-01\"", "\"maDonVi\":\"\"");
    assertThatThrownBy(() -> translator.translate(ctx("IOFFICE"), ioffice(unmapped)))
        .isInstanceOfSatisfying(
            TranslationRejectedException.class,
            e -> assertThat(e.reasonCode()).isEqualTo(DirectiveEvents.UNMAPPED_UNIT))
        .hasMessageContaining("DV-99");
    String none = IOFFICE.replaceAll("\"noiNhan\":\\[[^\\]]*\\]", "\"noiNhan\":[]");
    assertThatThrownBy(() -> translator.translate(ctx("IOFFICE"), ioffice(none)))
        .isInstanceOfSatisfying(
            TranslationRejectedException.class,
            e -> assertThat(e.reasonCode()).isEqualTo(DirectiveEvents.MISSING_UNIT));
  }

  @Test
  void vanBanMatBiTuChoiVanBanThuongKhongGhiDoMatVanNhan() {
    for (String level : List.of("MAT", "toi_mat", "TUYET_MAT")) {
      String secret = IOFFICE.replace("\"doMat\":\"THUONG\"", "\"doMat\":\"" + level + "\"");
      assertThatThrownBy(() -> translator.translate(ctx("IOFFICE"), ioffice(secret)))
          .isInstanceOfSatisfying(
              TranslationRejectedException.class,
              e -> assertThat(e.reasonCode()).isEqualTo(IOfficeTranslator.SECRET_DOCUMENT));
    }
    String open = IOFFICE.replace("\"doMat\":\"THUONG\",", "");
    assertThat(translator.translate(ctx("IOFFICE"), ioffice(open))).hasSize(1);
  }

  @Test
  void thieuTruongBatBuocHoacNoiDungHong() {
    String noNumber = IOFFICE.replace("\"soKyHieu\":\"12/CT-UBND\",", "");
    assertThatThrownBy(() -> translator.translate(ctx("IOFFICE"), ioffice(noNumber)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("soKyHieu");
    assertThatThrownBy(() -> translator.translate(ctx("IOFFICE"), ioffice("{")))
        .isInstanceOf(java.io.UncheckedIOException.class);
    assertThat(
            translator.translate(
                ctx("IOFFICE"), msg("VB-KHONG-PHIEN-BAN", IOfficeConnector.TYPE_JSON, IOFFICE)))
        .singleElement()
        .satisfies(e -> assertThat(e.aggregateVersion()).isEqualTo(1));
  }

  @Test
  void goiEdxmlChonNoiNhanDaAnhXa() {
    byte[] pkg =
        Edxml.build(
            new EdxmlDocument(
                "VB-E",
                new EdxmlDocument.Organ("000.00.00.H01", "Văn phòng Tỉnh uỷ"),
                List.of(
                    new EdxmlDocument.Organ("DV-99", "Chưa ánh xạ"),
                    new EdxmlDocument.Organ("DV-01", "Ban")),
                "15",
                "CV-VPTU",
                "Hà Nội",
                LocalDate.of(2026, 10, 18),
                "CV",
                "Triển khai nhiệm vụ quý IV",
                null,
                null,
                List.of(),
                false));
    List<CanonicalEvent> out =
        translator.translate(
            ctx("IOFFICE"), new InboundMessage("VB-E:1", IOfficeConnector.TYPE_EDXML, pkg));
    VanBanChiDaoDaNhan p = (VanBanChiDaoDaNhan) out.get(0).payload();
    assertThat(p.getDocNumber()).isEqualTo("15/CV-VPTU");
    assertThat(p.getIssuer()).isEqualTo("Văn phòng Tỉnh uỷ");
    assertThat(p.getIssuedDate()).isEqualTo(LocalDate.of(2026, 10, 18));
  }

  @Test
  void thoiDiemCoDoLechMuiGio() {
    assertThat(IOfficeConnector.instant("2026-10-20T08:00:00+07:00"))
        .isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));
    assertThat(IOfficeConnector.instant("20/10/2026")).isNull();
    assertThat(IOfficeConnector.instant(" ")).isNull();
    assertThat(IOfficeConnector.instant(null)).isNull();
  }
}
