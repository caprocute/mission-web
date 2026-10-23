package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.PersonnelSink;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** DV-13-08: ghi ánh xạ mã ngoài, xung đột khi đã gắn nơi khác mà không cho thay. */
class MappingCommandsTest {

  static final UUID T_ID = UUID.randomUUID();
  static final TenantId T = TenantId.of(T_ID);
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
  static final Connector HRM =
      new Connector(
          UUID.randomUUID(),
          T_ID,
          "HRM",
          Protocol.REST,
          "http://x",
          null,
          Direction.VAO,
          null,
          true);

  final Connectors connectors = mock(Connectors.class);
  final MappingXrefService mappings = mock(MappingXrefService.class);
  final MappingCommands commands =
      new MappingCommands(
          connectors,
          mappings,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC));

  @Test
  void ganMoiTuHomNayKhiKhongGhiNgay() {
    UUID kr = UUID.randomUUID();
    when(connectors.findByCode(T, "HRM")).thenReturn(Optional.of(HRM));
    when(mappings.resolve(T, HRM.id(), EntityType.CHI_SO, "CS-1", TODAY))
        .thenReturn(Optional.empty());
    when(mappings.link(T, HRM.id(), EntityType.CHI_SO, "CS-1", kr, TODAY, null)).thenReturn(true);
    assertThat(commands.link(T, "HRM", EntityType.CHI_SO, "CS-1", kr, null, false)).isTrue();
  }

  @Test
  void daGanNoiKhacThiXungDotTruKhiChoThay() {
    UUID old = UUID.randomUUID();
    UUID kr = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 11, 1);
    when(connectors.findByCode(T, "HRM")).thenReturn(Optional.of(HRM));
    when(mappings.resolve(T, HRM.id(), EntityType.CHI_SO, "CS-1", from))
        .thenReturn(Optional.of(old));
    assertThatThrownBy(() -> commands.link(T, "HRM", EntityType.CHI_SO, "CS-1", kr, from, false))
        .isInstanceOf(MappingConflictException.class);
    when(mappings.link(T, HRM.id(), EntityType.CHI_SO, "CS-1", kr, from, null)).thenReturn(true);
    assertThat(commands.link(T, "HRM", EntityType.CHI_SO, "CS-1", kr, from, true)).isTrue();
    verify(mappings).link(T, HRM.id(), EntityType.CHI_SO, "CS-1", kr, from, null);
    // Gắn lại đúng mã cũ là thao tác rỗng.
    when(mappings.link(T, HRM.id(), EntityType.CHI_SO, "CS-1", old, from, null)).thenReturn(false);
    assertThat(commands.link(T, "HRM", EntityType.CHI_SO, "CS-1", old, from, false)).isFalse();
  }

  @Test
  void ganLaiCungNgayHieuLucLaXungDot() {
    UUID kr = UUID.randomUUID();
    when(connectors.findByCode(T, "HRM")).thenReturn(Optional.of(HRM));
    when(mappings.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    when(mappings.link(eq(T), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Ánh xạ chồng thời gian hiệu lực"));
    assertThatThrownBy(() -> commands.link(T, "HRM", EntityType.NHAN_SU, "NS-1", kr, null, true))
        .isInstanceOf(MappingConflictException.class)
        .hasMessageContaining("chồng");
  }

  @Test
  void kiemThamSoVaKetNoi() {
    when(connectors.findByCode(T, "KHAC")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                commands.link(T, "KHAC", EntityType.CHI_SO, "CS-1", UUID.randomUUID(), null, false))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> commands.link(T, "HRM", EntityType.CHI_SO, "CS-1", null, null, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nhanSuKhongInDuLieuC2() {
    PersonnelSink.Person p =
        new PersonnelSink.Person(
            "NS-1", null, "Nguyễn Văn A", "001099000001", TODAY, "DANG_CONG_TAC", true, null, null);
    assertThat(p.posts()).isEmpty();
    assertThat(p.toString()).doesNotContain("001099000001", "Nguyễn");
    assertThat(
            new PersonnelSink.Person(
                    "NS-2",
                    null,
                    "B",
                    null,
                    null,
                    null,
                    false,
                    List.of(
                        new PersonnelSink.Post(
                            UUID.randomUUID(), UUID.randomUUID(), "CHINH", true, null)),
                    null)
                .posts())
        .hasSize(1);
  }
}
