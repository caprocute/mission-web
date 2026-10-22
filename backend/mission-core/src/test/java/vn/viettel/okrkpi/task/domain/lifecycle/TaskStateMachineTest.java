package vn.viettel.okrkpi.task.domain.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Bảng chuyển mặc định TL-02a QT-05 và lõi máy trạng thái (CORE-02-13). */
class TaskStateMachineTest {

  private static final List<String> COMMANDS =
      List.of("ASSIGN", "ACCEPT", "SUBMIT", "APPROVE", "REWORK", "SUSPEND", "RESUME", "CANCEL");

  /** Đích mong đợi của mười dòng QT-05. */
  private static final Map<String, TaskStatus> EXPECTED =
      Map.of(
          "NHAP:ASSIGN", TaskStatus.DA_GIAO,
          "DA_GIAO:ACCEPT", TaskStatus.DANG_THUC_HIEN,
          "DANG_THUC_HIEN:SUBMIT", TaskStatus.CHO_NGHIEM_THU,
          "CHO_NGHIEM_THU:REWORK", TaskStatus.DANG_THUC_HIEN,
          "CHO_NGHIEM_THU:APPROVE", TaskStatus.HOAN_THANH,
          "DANG_THUC_HIEN:SUSPEND", TaskStatus.TAM_DUNG,
          "TAM_DUNG:RESUME", TaskStatus.DANG_THUC_HIEN,
          "DA_GIAO:CANCEL", TaskStatus.HUY,
          "DANG_THUC_HIEN:CANCEL", TaskStatus.HUY,
          "TAM_DUNG:CANCEL", TaskStatus.HUY);

  private static final TaskActor ALL_ROLES =
      new TaskActor(UUID.randomUUID(), Set.of("manager", "member", "leader"));

  private final TaskStateMachine pass =
      new TaskStateMachine(TransitionTables.defaults(), (cel, a) -> true);

  @Test
  void moiCapTrangThaiLenhChoKetQuaDung() {
    int accepted = 0;
    for (TaskStatus s : TaskStatus.values()) {
      for (String code : COMMANDS) {
        TransitionResult r = pass.apply(s, TaskCommand.of(code, "Lý do"), ALL_ROLES, true, Map::of);
        TaskStatus expected = EXPECTED.get(s + ":" + code);
        if (expected == null) {
          assertThat(r)
              .as("%s:%s", s, code)
              .isInstanceOfSatisfying(
                  TransitionResult.Rejected.class,
                  x -> assertThat(x.code()).isEqualTo(RejectionCode.TRANSITION_NOT_ALLOWED));
        } else {
          accepted++;
          assertThat(r)
              .as("%s:%s", s, code)
              .isInstanceOfSatisfying(
                  TransitionResult.Accepted.class,
                  x -> {
                    assertThat(x.to()).isEqualTo(expected);
                    assertThat(x.from()).isEqualTo(s);
                    assertThat(x.policyVersion()).isEqualTo(TransitionTables.DEFAULT_VERSION);
                  });
        }
      }
    }
    assertThat(accepted).isEqualTo(10);
  }

  @Test
  void trangThaiKetThucKhongChuyenTiep() {
    assertThat(TaskStatus.HOAN_THANH.isTerminal()).isTrue();
    assertThat(TaskStatus.HUY.isTerminal()).isTrue();
    assertThat(TaskStatus.TAM_DUNG.isTerminal()).isFalse();
    for (TaskStatus s : List.of(TaskStatus.HOAN_THANH, TaskStatus.HUY)) {
      for (String code : COMMANDS) {
        assertThat(TransitionTables.defaults().find(s, code)).isEmpty();
      }
    }
    assertThatThrownBy(
            () ->
                new TransitionTable(
                    List.of(
                        new Transition(
                            TaskStatus.HUY, "RESUME", TaskStatus.DA_GIAO, Set.of(), null, false)),
                    "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tuChoiTheoThuTuKyVaiTroLyDoGuard() {
    TaskActor member = new TaskActor(UUID.randomUUID(), Set.of("member"));
    TaskActor manager = new TaskActor(UUID.randomUUID(), Set.of("manager"));
    assertThat(
            code(
                pass.apply(TaskStatus.TAM_DUNG, new TaskCommand.Resume(), manager, false, Map::of)))
        .isEqualTo(RejectionCode.PERIOD_LOCKED);
    assertThat(
            code(pass.apply(TaskStatus.TAM_DUNG, new TaskCommand.Resume(), member, true, Map::of)))
        .isEqualTo(RejectionCode.ROLE_NOT_ALLOWED);
    assertThat(
            code(
                pass.apply(
                    TaskStatus.CHO_NGHIEM_THU,
                    new TaskCommand.RequestRework(" "),
                    manager,
                    true,
                    Map::of)))
        .isEqualTo(RejectionCode.REASON_REQUIRED);
    assertThat(
            code(
                pass.apply(
                    TaskStatus.DANG_THUC_HIEN,
                    new TaskCommand.Cancel(null),
                    manager,
                    true,
                    Map::of)))
        .isEqualTo(RejectionCode.REASON_REQUIRED);
    TaskStateMachine fail = new TaskStateMachine(TransitionTables.defaults(), (cel, a) -> false);
    assertThat(
            code(fail.apply(TaskStatus.TAM_DUNG, new TaskCommand.Resume(), manager, true, Map::of)))
        .isEqualTo(RejectionCode.GUARD_FAILED);
    // Dòng không có guard không dựng ngữ cảnh.
    assertThat(
            fail.apply(
                TaskStatus.NHAP,
                new TaskCommand.Assign(),
                manager,
                true,
                () -> {
                  throw new AssertionError("không được gọi");
                }))
        .isInstanceOf(TransitionResult.Accepted.class);
    TransitionResult.Accepted suspended =
        (TransitionResult.Accepted)
            pass.apply(
                TaskStatus.DANG_THUC_HIEN,
                new TaskCommand.Suspend("Chờ kinh phí"),
                manager,
                true,
                Map::of);
    assertThat(suspended.reason()).isEqualTo("Chờ kinh phí");
    assertThat(pass.table().policyVersion()).isEqualTo(TransitionTables.DEFAULT_VERSION);
  }

  @Test
  void lenhTuMaVaLyDo() {
    assertThat(COMMANDS.stream().map(c -> TaskCommand.of(c, "r").code()).toList())
        .containsExactlyElementsOf(COMMANDS);
    assertThat(TaskCommand.of("rework", "r").reason()).isEqualTo("r");
    assertThat(new TaskCommand.Suspend(null).reason()).isEmpty();
    assertThat(new TaskCommand.Accept().reason()).isEmpty();
    assertThatThrownBy(() -> TaskCommand.of("PUBLISH", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TaskCommand.of(null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bangTuPolicyPackKhopBangMacDinh() throws Exception {
    Path file = repoRoot().resolve("data/policy-pack/khoi-dang/quy-dinh-moi/task_lifecycle.yaml");
    Map<String, Object> lifecycle;
    try (InputStream in = Files.newInputStream(file)) {
      lifecycle = new Yaml().load(in);
    }
    TransitionTable fromPack = TransitionTables.fromPolicy(lifecycle, "quy-dinh-moi@2.1.0");
    assertThat(fromPack.policyVersion()).isEqualTo("quy-dinh-moi@2.1.0");
    assertThat(Set.copyOf(fromPack.transitions()))
        .isEqualTo(Set.copyOf(TransitionTables.defaults().transitions()));
    Map<TaskStatus, Long> perStatus =
        fromPack.transitions().stream()
            .collect(
                Collectors.groupingBy(
                    Transition::from,
                    () -> new EnumMap<>(TaskStatus.class),
                    Collectors.counting()));
    assertThat(perStatus).doesNotContainKeys(TaskStatus.HOAN_THANH, TaskStatus.HUY);
  }

  @Test
  void bangPolicyHongBiTuChoi() {
    assertThatThrownBy(() -> TransitionTables.fromPolicy(Map.of(), "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TransitionTables.fromPolicy(Map.of("transitions", List.of("x")), "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TransitionTables.fromPolicy(
                    Map.of(
                        "transitions",
                        List.of(Map.of("from", "NHAP", "command", "ASSIGN", "to", "QUA_HAN"))),
                    "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TransitionTables.fromPolicy(
                    Map.of("transitions", List.of(Map.of("from", "NHAP", "to", "DA_GIAO"))), "v"))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> row =
        Map.of(
            "from",
            "NHAP",
            "command",
            "ASSIGN",
            "to",
            "DA_GIAO",
            "reasonRequired",
            "true",
            "guardCel",
            " ");
    assertThatThrownBy(
            () -> TransitionTables.fromPolicy(Map.of("transitions", List.of(row, row)), "v"))
        .isInstanceOf(IllegalArgumentException.class);
    Transition one =
        TransitionTables.fromPolicy(Map.of("transitions", List.of(row)), "v")
            .find(TaskStatus.NHAP, "ASSIGN")
            .orElseThrow();
    assertThat(one.reasonRequired()).isTrue();
    assertThat(one.guardCel()).isNull();
    assertThat(one.allowedRoles()).isEmpty();
  }

  private static RejectionCode code(TransitionResult r) {
    return ((TransitionResult.Rejected) r).code();
  }

  private static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("data/policy-pack"))) {
      p = p.getParent();
    }
    return p;
  }
}
