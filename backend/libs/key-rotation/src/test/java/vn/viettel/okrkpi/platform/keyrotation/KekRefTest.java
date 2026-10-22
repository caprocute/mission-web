package vn.viettel.okrkpi.platform.keyrotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.kernel.ActorId;

class KekRefTest {

  @Test
  void docVaGhiKekRefTheoCacDangCuaTl03VaInf13() {
    KekRef a = KekRef.parse("transit/keys/kek-tenant-01950000-0000-7000-8000-000000000001");
    assertThat(a.mount()).isEqualTo("transit");
    assertThat(a.keyName()).isEqualTo("kek-tenant-01950000-0000-7000-8000-000000000001");
    assertThat(a.version()).isNull();
    KekRef b = KekRef.parse("transit/keys/t-hcm-c2@v3");
    assertThat(b.version()).isEqualTo(3);
    assertThat(b.withVersion(4).toString()).isEqualTo("transit/keys/t-hcm-c2@v4");
    assertThat(KekRef.parse("cell/l1/transit/keys/kek-x").mount()).isEqualTo("cell/l1/transit");
    assertThatThrownBy(() -> KekRef.parse("perf/kek-thu"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void phienBanCuaBanBocVault() {
    assertThat(KekRef.wrappedVersion("vault:v12:abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo(12);
    assertThatThrownBy(() -> KekRef.wrappedVersion(new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pheDuyetHaiNguoiKhacNhauVaCoPhieu() {
    ActorId x = ActorId.of(UUID.randomUUID());
    ActorId y = ActorId.of(UUID.randomUUID());
    assertThat(new Approval(x, y, "SC-1").reference()).isEqualTo("SC-1");
    assertThatThrownBy(() -> new Approval(x, x, "SC-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Approval(x, y, " ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chiSoVaSuKienChiCoSoDem(@TempDir Path dir) throws Exception {
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    List<AuditEvent> events = new ArrayList<>();
    RotationReporter r = new RotationReporter(reg, events::add, ActorId.of(UUID.randomUUID()));
    r.keyAge("KHIEU_NAI", 31);
    r.keyAge("KHIEU_NAI", 2);
    r.items("dek_thang", "dek_moi", 3);
    r.items("dek_thang", "dek_moi", 0);
    r.remaining("dek_ma_hoa_lai", 7);
    r.audit("KEY_ROTATION_DEK_MONTHLY", UUID.randomUUID(), null, null, Map.of("so_dek_moi", "3"));
    String text = KeyRotationMain.prometheusText(reg);
    assertThat(text)
        .contains("vt_key_age_days{loai=\"dek\",muc_dich=\"KHIEU_NAI\"} 2")
        .contains("vt_key_rotation_items_total{job=\"dek_thang\",ket_qua=\"dek_moi\"} 3")
        .contains("vt_key_rotation_remaining{job=\"dek_ma_hoa_lai\"} 7")
        .contains("# TYPE vt_key_age_days gauge");
    assertThat(events)
        .singleElement()
        .satisfies(e -> assertThat(e.objectType()).isEqualTo("keyring.dek"));

    Path f = dir.resolve("tenants.txt");
    UUID t = UUID.randomUUID();
    Files.writeString(f, "# danh sách\n" + t + "\n\n");
    assertThat(KeyRotationMain.readTenants(f)).containsExactly(t);
  }
}
