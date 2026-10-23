package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.IndicatorSource.Reading;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Located;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Mapping;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * DV-13-08: số liệu nguồn của KR qua {@code mapping_xref} {@code CHI_SO}, nguồn chậm 10 giây bị cắt
 * ở thời hạn, ngắt mạch mở sau chuỗi lỗi, chọn kết nối theo tên nguồn MOD-01 gửi.
 */
class KrSourceValueServiceTest {

  static final UUID T_ID = UUID.randomUUID();
  static final TenantId T = TenantId.of(T_ID);
  static final UUID KR = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** Nguồn giả: chế độ trả giá trị, chậm, lỗi hoặc rỗng. */
  static final class FakeSource implements IndicatorSource {
    final String code;
    volatile String mode = "ok";
    final List<String> calls = new ArrayList<>();

    FakeSource(String code) {
      this.code = code;
    }

    @Override
    public String connectorCode() {
      return code;
    }

    @Override
    public Reading read(Connector connector, String externalIndicatorId) throws IOException {
      calls.add(externalIndicatorId);
      switch (mode) {
        case "slow" -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("bị ngắt", e);
          }
          return new Reading(BigDecimal.ONE, null);
        }
        case "error" -> throw new IOException("lỗi giả");
        case "empty" -> {
          return null;
        }
        case "notime" -> {
          return new Reading(new BigDecimal("7"), null);
        }
        default -> {
          return new Reading(new BigDecimal("2.6"), Instant.parse("2026-10-22T01:00:00Z"));
        }
      }
    }
  }

  final Connectors connectors = mock(Connectors.class);
  final MappingXrefs xrefs = mock(MappingXrefs.class);
  final FakeSource hrm = new FakeSource("HRM");
  final FakeSource his = new FakeSource("HIS");
  ExecutorService executor;

  static Connector connector(String code, boolean enabled, Direction direction) {
    return new Connector(
        UUID.nameUUIDFromBytes(code.getBytes()),
        T_ID,
        code,
        Protocol.REST,
        "http://nguon.local",
        null,
        direction,
        null,
        enabled);
  }

  static Located located(Connector c, String external) {
    return new Located(
        c.id(), new Mapping(UUID.randomUUID(), external, KR, LocalDate.of(2026, 1, 1)));
  }

  void mapped(Connector... cs) {
    List<Located> list = new ArrayList<>();
    for (Connector c : cs) {
      when(connectors.findById(T, c.id())).thenReturn(Optional.of(c));
      list.add(located(c, "CS-" + c.code()));
    }
    when(xrefs.byInternalAnyConnector(eq(T), eq(EntityType.CHI_SO), eq(KR), any()))
        .thenReturn(list);
  }

  KrSourceValueService service(Duration timeout, CircuitBreakerRegistry breakers) {
    return new KrSourceValueService(
        connectors,
        xrefs,
        List.of(hrm, his),
        TransactionOperations.withoutTransaction(),
        CLOCK,
        timeout,
        breakers,
        executor);
  }

  KrSourceValueService service() {
    return service(Duration.ofMillis(500), CircuitBreakerRegistry.ofDefaults());
  }

  @BeforeEach
  void start() {
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void stop() {
    executor.shutdownNow();
  }

  @Test
  void layGiaTriTuChiSoDaGanVoiKr() {
    mapped(connector("HRM", true, Direction.VAO));
    var v = service().read(T, KR, "CSDL cán bộ");
    assertThat(v.value()).isEqualByComparingTo("2.6");
    assertThat(v.fetchedAt()).isEqualTo(Instant.parse("2026-10-22T01:00:00Z"));
    assertThat(v.dataSource()).isEqualTo("CSDL cán bộ");
    assertThat(v.connectorCode()).isEqualTo("HRM");
    assertThat(hrm.calls).containsExactly("CS-HRM");
  }

  @Test
  void nguonKhongGhiThoiDiemThiLayGioHienTaiVaTenNguonLaMaKetNoi() {
    mapped(connector("HRM", true, Direction.HAI_CHIEU));
    hrm.mode = "notime";
    var v = service().read(T, KR, null);
    assertThat(v.fetchedAt()).isEqualTo(NOW);
    assertThat(v.dataSource()).isEqualTo("HRM");
    assertThat(service().read(T, KR, " ").dataSource()).isEqualTo("HRM");
  }

  @Test
  void uuTienKetNoiTrungTenNguonVaBoQuaKetNoiTatHoacChiGuiRa() {
    mapped(
        connector("TAT", false, Direction.VAO),
        connector("RA_NGOAI", true, Direction.RA),
        connector("KHONG_NGUON", true, Direction.VAO),
        connector("HRM", true, Direction.VAO),
        connector("HIS", true, Direction.VAO));
    assertThat(service().read(T, KR, "his").connectorCode()).isEqualTo("HIS");
    assertThat(service().read(T, KR, "Khác").connectorCode()).isEqualTo("HRM");
    assertThat(his.calls).containsExactly("CS-HIS");
  }

  @Test
  void krChuaGanChiSoLaKhongTimThay() {
    when(xrefs.byInternalAnyConnector(any(), any(), any(), any())).thenReturn(List.of());
    assertThatThrownBy(() -> service().read(T, KR, null))
        .isInstanceOf(NoSuchElementException.class);
    mapped(connector("TAT", false, Direction.VAO));
    assertThatThrownBy(() -> service().read(T, KR, null))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nguonCham10GiayBiCatTrongThoiHan() {
    mapped(connector("HRM", true, Direction.VAO));
    hrm.mode = "slow";
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                service(Duration.ofMillis(2500), CircuitBreakerRegistry.ofDefaults())
                    .read(T, KR, null))
        .isInstanceOfSatisfying(
            SourceUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(SourceUnavailableException.Reason.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void nguonLoiHoacRongLaKhongDungDuocVaMachMoSauChuoiLoi() {
    mapped(connector("HRM", true, Direction.VAO));
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    KrSourceValueService s = service(Duration.ofMillis(500), breakers);
    hrm.mode = "empty";
    assertThatThrownBy(() -> s.read(T, KR, null))
        .isInstanceOfSatisfying(
            SourceUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(SourceUnavailableException.Reason.ERROR));
    hrm.mode = "error";
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> s.read(T, KR, null)).isInstanceOf(SourceUnavailableException.class);
    }
    int before = hrm.calls.size();
    assertThatThrownBy(() -> s.read(T, KR, null))
        .isInstanceOfSatisfying(
            SourceUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(SourceUnavailableException.Reason.CIRCUIT_OPEN));
    assertThat(hrm.calls).hasSize(before);
  }

  @Test
  void kiemThamSo() {
    assertThatThrownBy(() -> service().read(T, KR, "x".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service(Duration.ZERO, CircuitBreakerRegistry.ofDefaults()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service(null, CircuitBreakerRegistry.ofDefaults()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new KrSourceValueService(
                    connectors,
                    xrefs,
                    List.of(hrm, new FakeSource("HRM")),
                    TransactionOperations.withoutTransaction(),
                    CLOCK,
                    Duration.ofSeconds(1),
                    CircuitBreakerRegistry.ofDefaults(),
                    executor))
        .isInstanceOf(IllegalStateException.class);
  }
}
