package vn.viettel.okrkpi.integ.infrastructure.lgsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Chỉ tiêu KT-XH qua LGSP: cờ đã công bố chỉ bật theo LGSP, dữ liệu sai vào thư chết. */
class LgspIndicatorTranslatorTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final Connector C =
      new Connector(
          UUID.randomUUID(),
          T.value(),
          "LGSP",
          Protocol.REST,
          "https://lgsp.invalid",
          "kv/x",
          Direction.VAO,
          null,
          true);
  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

  final KtxhValues values = mock(KtxhValues.class);
  final LgspIndicatorTranslator translator =
      new LgspIndicatorTranslator(values, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
  final TranslationContext ctx =
      new TranslationContext(T, C, LocalDate.of(2026, 10, 23), mock(MappingXrefService.class));

  static InboundMessage msg(String json) {
    return new InboundMessage(
        "x", LgspIndicatorConnector.TYPE, json.getBytes(StandardCharsets.UTF_8));
  }

  KtxhValues.Value saved() {
    ArgumentCaptor<KtxhValues.Value> v = ArgumentCaptor.forClass(KtxhValues.Value.class);
    verify(values).upsert(eq(T), any(), eq(C.id()), v.capture(), eq(NOW));
    return v.getValue();
  }

  @Test
  void daCongBoGhiThoiDiemCongBo() {
    assertThat(
            translator.translate(
                ctx,
                msg(
                    "{\"maChiTieu\":\"GRDP\",\"kyBaoCao\":\"2026-Q2\",\"maDiaBan\":\"T01\",\"giaTri\":7.2,\"donViTinh\":\"%\",\"daCongBo\":true,\"thoiDiemCongBo\":\"2026-07-15T09:00:00+07:00\",\"capNhatLuc\":\"2026-07-15T09:00:00+07:00\"}")))
        .isEmpty();
    KtxhValues.Value v = saved();
    assertThat(v.value()).isEqualByComparingTo(new BigDecimal("7.2"));
    assertThat(v.publishedAt()).isEqualTo(Instant.parse("2026-07-15T02:00:00Z"));
  }

  @Test
  void chuaCongBoKhongCoThoiDiemCongBo() {
    translator.translate(
        ctx,
        msg(
            "{\"maChiTieu\":\"CPI\",\"kyBaoCao\":\"2026-Q3\",\"maDiaBan\":\"T01\",\"giaTri\":\"3.1\",\"daCongBo\":false,\"thoiDiemCongBo\":\"2026-10-01T09:00:00+07:00\"}"));
    KtxhValues.Value v = saved();
    assertThat(v.publishedAt()).isNull();
    assertThat(v.unit()).isNull();
  }

  @Test
  void duLieuSaiBiTuChoi() {
    String[] bad = {
      "không phải json",
      "[1]",
      "{\"kyBaoCao\":\"2026\",\"maDiaBan\":\"T01\",\"giaTri\":1}",
      "{\"maChiTieu\":\"A\",\"kyBaoCao\":\"x\",\"maDiaBan\":\"T01\",\"giaTri\":1}",
      "{\"maChiTieu\":\"A\",\"kyBaoCao\":\"2026\",\"giaTri\":1}",
      "{\"maChiTieu\":\"A\",\"kyBaoCao\":\"2026\",\"maDiaBan\":\"T01\",\"giaTri\":\"abc\"}",
      "{\"maChiTieu\":\"A\",\"kyBaoCao\":\"2026\",\"maDiaBan\":\"T01\",\"giaTri\":1e30}",
      "{\"maChiTieu\":\"A\",\"kyBaoCao\":\"2026\",\"maDiaBan\":\"T01\",\"giaTri\":1,\"daCongBo\":true}"
    };
    for (String b : bad) {
      assertThatThrownBy(() -> translator.translate(ctx, msg(b)))
          .as(b)
          .isInstanceOfSatisfying(
              TranslationRejectedException.class,
              e -> assertThat(e.reasonCode()).isEqualTo(LgspIndicatorTranslator.BAD_STRUCTURE));
    }
  }

  @Test
  void thoiDiemSaiDinhDang() {
    assertThat(LgspIndicatorConnector.instant("x")).isNull();
    assertThat(LgspIndicatorConnector.instant(null)).isNull();
  }
}
