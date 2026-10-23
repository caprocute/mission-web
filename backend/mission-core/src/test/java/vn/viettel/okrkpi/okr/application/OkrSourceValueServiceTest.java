package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinCommand;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.SourceTarget;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort.SourceValue;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-01-10: lấy số liệu nguồn, đệm theo KR và người lấy, thay giá trị nguồn client gửi. */
class OkrSourceValueServiceTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID KR = UUID.randomUUID();
  static final UUID OWNER = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final Instant AT = Instant.parse("2026-10-22T01:00:00Z");
  static final String CSDL = "CSDL đảng viên";

  /** Nguồn giả: trả giá trị đặt sẵn hoặc lỗi, đếm số lần gọi. */
  static final class FakeSource implements KrSourceValuePort {
    BigDecimal value = new BigDecimal("2.6");
    boolean down;
    final List<String> calls = new ArrayList<>();

    @Override
    public SourceValue fetch(TenantId tenant, UUID keyResultId, String dataSource) {
      calls.add(dataSource);
      if (down) {
        throw new Unavailable("lỗi giả", null);
      }
      return new SourceValue(value, AT, null);
    }
  }

  private final FakeSource source = new FakeSource();
  private final AtomicLong nanos = new AtomicLong();
  private Optional<SourceTarget> target = Optional.of(new SourceTarget(KR, CSDL));

  private OkrSourceValueService service(KrSourceValuePort port) {
    return new OkrSourceValueService(
        (tenant, actor, kr) -> target, () -> port, Duration.ofMinutes(30), nanos::get);
  }

  private static CheckinCommand cmd(String value, String sourceValue) {
    return new CheckinCommand(
        "DA_GUI",
        new BigDecimal(value),
        "CAO",
        sourceValue == null ? null : new BigDecimal(sourceValue),
        Instant.parse("2020-01-01T00:00:00Z"),
        null,
        null,
        null,
        "WEB",
        false);
  }

  @Test
  void layGiaTriGanTenNguonCuaKr() {
    SourceValue v = service(source).fetch(T, OWNER, KR);
    assertThat(v).isEqualTo(new SourceValue(new BigDecimal("2.6"), AT, CSDL));
    assertThat(source.calls).containsExactly(CSDL);
  }

  @Test
  void nguonLoiHoacChuaCauHinhTra502KemTenNguon() {
    source.down = true;
    assertThatThrownBy(() -> service(source).fetch(T, OWNER, KR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("OKR-502-SOURCE-UNAVAILABLE");
              assertThat(e.detail())
                  .isEqualTo("Không lấy được số liệu từ CSDL đảng viên. Đồng chí có thể nhập tay.");
            });
    assertThatThrownBy(() -> service(null).fetch(T, OWNER, KR))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void krKhongGanNguonLaKhongTimThay() {
    target = Optional.empty();
    assertThatThrownBy(() -> service(source).fetch(T, OWNER, KR))
        .isInstanceOf(OkrCheckinService.NotFound.class);
    assertThat(service(source).verify(T, OWNER, KR, cmd("3", "2.6")).sourceValue()).isNull();
  }

  @Test
  void giaTriNguonClientGuiDuocThayBangGiaTriTrongDem() {
    OkrSourceValueService s = service(source);
    s.fetch(T, OWNER, KR);
    source.value = new BigDecimal("4");
    // Client sửa giá trị nguồn thành 3 để khỏi ghi lý do: máy chủ giữ 2,6 đã lấy.
    CheckinCommand v = s.verify(T, OWNER, KR, cmd("3", "3"));
    assertThat(v.sourceValue()).isEqualByComparingTo("2.6");
    assertThat(v.sourceFetchedAt()).isEqualTo(AT);
    assertThat(v.value()).isEqualByComparingTo("3");
    assertThat(source.calls).hasSize(1);
  }

  @Test
  void hetDemHoacNguoiKhacThiLayLai() {
    OkrSourceValueService s = service(source);
    s.fetch(T, OWNER, KR);
    source.value = new BigDecimal("5");
    assertThat(s.verify(T, OTHER, KR, cmd("5", "2.6")).sourceValue()).isEqualByComparingTo("5");
    nanos.addAndGet(Duration.ofMinutes(31).toNanos());
    source.value = new BigDecimal("6");
    assertThat(s.verify(T, OWNER, KR, cmd("6", "2.6")).sourceValue()).isEqualByComparingTo("6");
    assertThat(source.calls).hasSize(3);
  }

  @Test
  void khongGuiGiaTriNguonHoacNguonLoiLaNhapTay() {
    OkrSourceValueService s = service(source);
    CheckinCommand none = s.verify(T, OWNER, KR, cmd("3", null));
    assertThat(none.sourceValue()).isNull();
    assertThat(none.sourceFetchedAt()).isNull();
    assertThat(source.calls).isEmpty();
    source.down = true;
    CheckinCommand down = s.verify(T, OWNER, KR, cmd("3", "2.6"));
    assertThat(down.sourceValue()).isNull();
    assertThat(down.sourceFetchedAt()).isNull();
  }

  @Test
  void hanDemKhongHopLeDungMacDinh() {
    assertThat(
            new OkrSourceValueService((t, a, k) -> target, () -> source, Duration.ZERO)
                .fetch(T, OWNER, KR))
        .isNotNull();
    assertThat(new OkrSourceValueService((t, a, k) -> target, () -> source, null)).isNotNull();
  }
}
