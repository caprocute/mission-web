package vn.viettel.okrkpi.workflow.application.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.workflow.application.port.ApprovalProcesses;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * Khởi tạo luồng duyệt theo yêu cầu của module lõi (ADR-04 tầng 1 → tầng 2). Cổng vào là API nội
 * bộ trong tiến trình (lớp này); bộ chuyển HTTP chờ hợp đồng có thao tác khởi tạo (ghi ở wiki trang
 * 14). Khoá nghiệp vụ là id đối tượng; idempotent theo (tenant, mẫu, khoá nghiệp vụ, phiên bản đối
 * tượng): gửi lại cùng yêu cầu trả tiến trình đã có.
 *
 * <p>Tham số đọc từ {@code flowable.wf_binding}; thiếu dòng thì dùng mặc định của khối. Định nghĩa
 * BPMN dùng chung, tenant đặt trên thể hiện, nên số định nghĩa không tăng theo số tenant.
 */
@Service
public class StartApprovalService {

  /** Nguyên nhân theo hợp đồng {@code CauseKind}. */
  static final Set<String> CAUSES = Set.of("KHACH_QUAN", "CHU_QUAN");

  /** Độ dài tối đa của tiêu đề (cột {@code wf_task_projection.title}). */
  static final int MAX_TITLE = 255;

  /** Độ dài tối đa của mã phiên bản Policy Pack, ví dụ {@code khoi-chinh-quyen/nd335@1.4}. */
  static final int MAX_POLICY_PACK = 64;

  private final ApprovalProcesses processes;
  private final BindingReader bindings;

  public StartApprovalService(ApprovalProcesses processes, BindingReader bindings) {
    this.processes = processes;
    this.bindings = bindings;
  }

  /** Khởi tạo hoặc trả lại tiến trình đã khởi tạo cho cùng đối tượng và phiên bản. */
  @Transactional(timeout = 10)
  public Result start(StartApprovalCommand command) {
    ApprovalTemplate template = command.template();
    validate(command);
    BusinessKey key = new BusinessKey(template.businessType(), command.businessId());
    processes.lockStart(command.tenantId(), template.code(), key);
    Optional<String> existing =
        processes.findStarted(command.tenantId(), template.code(), key, command.objectVersion());
    if (existing.isPresent()) {
      return new Result(existing.get(), false);
    }
    ApprovalParams params =
        bindings
            .find(command.tenantId(), template.processKind(), command.flow())
            .orElseGet(() -> ApprovalParams.defaults(command.flow()));
    Map<String, Object> variables = variables(command, params);
    if (ApprovalVariables.sizeOf(variables) > ApprovalVariables.MAX_START_BYTES) {
      throw new ApprovalSetupException("Biến khởi tạo tiến trình vượt ngân sách 1,5 KB");
    }
    String id =
        processes.start(
            command.tenantId(), template.code(), key, command.initiatorId(), variables);
    return new Result(id, true);
  }

  private static void validate(StartApprovalCommand command) {
    if (command.objectVersion() < 0) {
      throw new ApprovalSetupException("Phiên bản đối tượng không hợp lệ");
    }
    if (command.title().isBlank() || command.title().length() > MAX_TITLE) {
      throw new ApprovalSetupException("Tiêu đề việc phải có và tối đa 255 ký tự");
    }
    if (command.policyPackVersion() != null
        && command.policyPackVersion().length() > MAX_POLICY_PACK) {
      throw new ApprovalSetupException("Mã phiên bản Policy Pack quá dài");
    }
    switch (command.template()) {
      case DUYET_KE_HOACH_QUY -> {
        if (command.planVersionNo() == null || command.planVersionNo() < 1) {
          throw new ApprovalSetupException("Thiếu số phiên bản kế hoạch");
        }
      }
      case DUYET_DE_NGHI -> {
        if (command.taskId() == null) {
          throw new ApprovalSetupException("Thiếu nhiệm vụ của đề nghị");
        }
        if (!CAUSES.contains(command.causeClaimed())) {
          throw new ApprovalSetupException("Nguyên nhân đề nghị không thuộc danh mục");
        }
      }
      case DUYET_OKR -> {
        // Không có trường riêng.
      }
    }
  }

  private static Map<String, Object> variables(StartApprovalCommand c, ApprovalParams params) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put(ApprovalVariables.TENANT_ID, c.tenantId().toString());
    v.put(ApprovalVariables.KHOI, c.flow().name());
    v.put(ApprovalVariables.LOAI_HO_SO, c.template().processKind());
    v.put(ApprovalVariables.LA_NGUOI_DUNG_DAU, c.headOfUnit());
    v.put(ApprovalVariables.DON_VI_ID, c.subjectUnitId().toString());
    v.put(ApprovalVariables.TIEU_DE, c.title().strip());
    v.put(ApprovalVariables.PHIEN_BAN_DOI_TUONG, c.objectVersion());
    v.put(ApprovalVariables.REMIND_AFTER, params.remindAfter());
    v.put(ApprovalVariables.CHO_PHEP_UY_QUYEN, params.allowDelegation());
    if (!params.levels().isEmpty()) {
      v.put(ApprovalVariables.CAP_TUY_CHINH, ApprovalVariables.encodeRules(params.levels()));
    }
    if (c.periodId() != null) {
      v.put(ApprovalVariables.PERIOD_ID, c.periodId().toString());
    }
    if (c.policyPackVersion() != null) {
      v.put(ApprovalVariables.PHIEN_BAN_POLICY_PACK, c.policyPackVersion());
    }
    switch (c.template()) {
      case DUYET_KE_HOACH_QUY -> {
        v.put(ApprovalVariables.KE_HOACH_PHIEN_BAN, c.planVersionNo());
        v.put(ApprovalVariables.PHUONG_THUC_KY, params.signMethod());
      }
      case DUYET_DE_NGHI -> {
        v.put(ApprovalVariables.NHIEM_VU_ID, c.taskId().toString());
        v.put(ApprovalVariables.NGUYEN_NHAN, c.causeClaimed());
      }
      case DUYET_OKR -> {
        // Không có biến riêng.
      }
    }
    return v;
  }

  /**
   * Kết quả khởi tạo.
   *
   * @param processInstanceId mã thể hiện tiến trình
   * @param created {@code false} khi yêu cầu lặp lại trả tiến trình đã có
   */
  public record Result(String processInstanceId, boolean created) {}
}
