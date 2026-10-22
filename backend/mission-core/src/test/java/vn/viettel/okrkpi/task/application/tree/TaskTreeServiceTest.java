package vn.viettel.okrkpi.task.application.tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.tree.port.TaskTreeRows;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Node;
import vn.viettel.okrkpi.task.domain.tree.TaskTree.AssignmentMode;

class TaskTreeServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();

  private final Map<UUID, TaskTreeRows.NodeRow> nodes = new LinkedHashMap<>();
  private final Map<String, Object> params = new LinkedHashMap<>();

  private final TaskTreeRows rows =
      new TaskTreeRows() {
        @Override
        public Optional<NodeRow> node(TenantId tenant, UUID id) {
          return Optional.ofNullable(nodes.get(id));
        }

        @Override
        public List<NodeRow> path(TenantId tenant, UUID id) {
          List<NodeRow> out = new ArrayList<>();
          for (NodeRow n = nodes.get(id); n != null; n = nodes.get(n.parentId())) {
            out.add(0, n);
            if (n.parentId() == null) {
              break;
            }
          }
          return out;
        }

        @Override
        public List<NodeRow> children(TenantId tenant, UUID parentId) {
          return nodes.values().stream().filter(n -> parentId.equals(n.parentId())).toList();
        }
      };

  private final PolicyQueryApi pack =
      new PolicyQueryApi() {
        final UUID version = UUID.randomUUID();

        @Override
        public Optional<PolicyFormulaView> findActivePolicy(
            TenantId tenantId, PeriodId periodId, String code) {
          return Optional.empty();
        }

        @Override
        public Optional<ResolvedPack> resolve(
            TenantId tenantId, PeriodId periodId, String flow, UUID group, UUID unit) {
          return Optional.of(
              new ResolvedPack(UUID.randomUUID(), "PP", version, "1", "h", null, version, false));
        }

        @Override
        public Optional<Object> parameter(TenantId tenantId, UUID versionId, String key) {
          return Optional.ofNullable(params.get(key));
        }
      };

  private final TaskTreeService service = new TaskTreeService(rows, pack);

  private UUID add(String kind, UUID parent, String status, String standard, String component) {
    UUID id = UUID.randomUUID();
    nodes.put(
        id,
        new TaskTreeRows.NodeRow(
            id,
            PERIOD,
            kind,
            parent,
            status,
            standard == null ? null : new BigDecimal(standard),
            component == null ? null : new BigDecimal(component)));
    return id;
  }

  @Test
  void viTriKiemCungKyKhongVongVaDungBac() {
    UUID root = add("NHIEM_VU", null, "DA_GIAO", "50", null);
    UUID job = add("CONG_VIEC", root, "NHAP", null, "20");
    service.requirePlacement(TENANT, null, PERIOD, new Node("CONG_VIEC_CON", job, null));
    assertThatThrownBy(
            () -> service.requirePlacement(TENANT, null, PERIOD, new Node("KPI", job, null)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.requirePlacement(TENANT, root, PERIOD, new Node("CONG_VIEC", job, null)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("cấp dưới"));
    assertThatThrownBy(
            () ->
                service.requirePlacement(
                    TENANT, null, UUID.randomUUID(), new Node("CONG_VIEC", root, null)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("cùng kỳ"));

    params.put(TaskTreeService.TEMPLATE_KEY, List.of("NHIEM_VU", "CONG_VIEC"));
    assertThatThrownBy(
            () ->
                service.requirePlacement(
                    TENANT, null, PERIOD, new Node("CONG_VIEC_CON", job, null)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("khuôn phân rã"));
  }

  @Test
  void haiThamSoCheDoPhanCong() {
    UUID root = add("NHIEM_VU", null, "DA_GIAO", "50", null);
    add("CONG_VIEC", root, "DA_GIAO", null, "30");
    add("CONG_VIEC", root, "NHAP", null, "30");
    Node child = new Node("CONG_VIEC", root, new BigDecimal("25"));

    assertThat(service.mode(TENANT, PERIOD)).isEqualTo(AssignmentMode.CHU_TRI_PHOI_HOP);
    service.requireMode(TENANT, null, PERIOD, child, 2, false);

    params.put(TaskTreeService.MODE_KEY, "MOT_NGUOI_MOT_VIEC");
    assertThat(
            service.violations(
                TENANT, null, PERIOD, new Node("CONG_VIEC", root, new BigDecimal("20")), 1, false))
        .isEmpty();
    assertThatThrownBy(() -> service.requireMode(TENANT, null, PERIOD, child, 2, false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo(TaskTreeService.MODE_ERROR);
              assertThat(e.errors()).hasSize(2);
            });
    // Việc cha (có việc con) không bị ràng buộc một người.
    assertThat(service.violations(TENANT, root, PERIOD, new Node("NHIEM_VU", null, null), 3, false))
        .isEmpty();
  }

  @Test
  void tongHopViecCha() {
    params.put(TaskTreeService.MODE_KEY, "MOT_NGUOI_MOT_VIEC");
    UUID root = add("NHIEM_VU", null, "DA_GIAO", "50", null);
    add("CONG_VIEC", root, "HOAN_THANH", null, "30");
    add("CONG_VIEC", root, "HOAN_THANH", null, "20");
    assertThat(service.rollup(TENANT, root).orElseThrow().done()).isTrue();
    assertThat(service.rollup(TENANT, UUID.randomUUID())).isEmpty();
  }

  @Test
  void diemCuaNutLamViecCha() {
    UUID p = UUID.randomUUID();
    BigDecimal ten = BigDecimal.TEN;
    BigDecimal one = BigDecimal.ONE;
    assertThat(new TaskTreeRows.NodeRow(p, PERIOD, "KPI", p, "NHAP", ten, one).score())
        .isEqualTo(one);
    assertThat(new TaskTreeRows.NodeRow(p, PERIOD, "KPI", p, "NHAP", ten, null).score())
        .isEqualTo(ten);
    assertThat(new TaskTreeRows.NodeRow(p, PERIOD, "NHIEM_VU", null, "NHAP", ten, one).score())
        .isEqualTo(ten);
  }
}
