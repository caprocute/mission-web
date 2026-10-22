package vn.viettel.okrkpi.task.domain.assign;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Nội dung nhiệm vụ đang soạn của SCR-0501 ({@code TaskDraft} của hợp đồng, bỏ các trường chỉ đọc).
 * {@code source} là {@code KE_HOACH}, {@code VAN_BAN}, {@code DANG_KY} hoặc {@code DOT_XUAT};
 * {@code priority} là {@code CAO}, {@code TRUNG_BINH} hoặc {@code THAP}.
 */
public record TaskDraftSpec(
    UUID periodId,
    String source,
    UUID planItemId,
    UUID catalogItemId,
    String title,
    String taskGroup,
    UUID keyResultId,
    String priority,
    UUID leadPersonId,
    List<Assignee> coAssignees,
    LocalDate dueDate,
    UUID acceptorId,
    List<Milestone> milestones,
    String productName,
    BigDecimal quantity,
    String unit,
    String evidenceRequired,
    String qualityCriteria,
    String sourceDocNumber,
    String sourceDocTitle,
    String basis,
    Node node) {

  public TaskDraftSpec {
    coAssignees = List.copyOf(coAssignees == null ? List.of() : coAssignees);
    milestones = List.copyOf(milestones == null ? List.of() : milestones);
    node = node == null ? Node.ROOT : node;
  }

  /** Bản soạn không nằm trong cây phân rã (nút gốc {@code NHIEM_VU}). */
  public TaskDraftSpec(
      UUID periodId,
      String source,
      UUID planItemId,
      UUID catalogItemId,
      String title,
      String taskGroup,
      UUID keyResultId,
      String priority,
      UUID leadPersonId,
      List<Assignee> coAssignees,
      LocalDate dueDate,
      UUID acceptorId,
      List<Milestone> milestones,
      String productName,
      BigDecimal quantity,
      String unit,
      String evidenceRequired,
      String qualityCriteria,
      String sourceDocNumber,
      String sourceDocTitle,
      String basis) {
    this(
        periodId,
        source,
        planItemId,
        catalogItemId,
        title,
        taskGroup,
        keyResultId,
        priority,
        leadPersonId,
        coAssignees,
        dueDate,
        acceptorId,
        milestones,
        productName,
        quantity,
        unit,
        evidenceRequired,
        qualityCriteria,
        sourceDocNumber,
        sourceDocTitle,
        basis,
        Node.ROOT);
  }

  /**
   * Vị trí trong cây phân rã (BS-03 DC-04, DC-06): loại nút, việc cha và điểm thành phần; {@code
   * kind} rỗng là {@code NHIEM_VU}.
   */
  public record Node(String kind, UUID parentTaskId, BigDecimal componentScore) {

    public static final Node ROOT = new Node("NHIEM_VU", null, null);

    public Node {
      kind = kind == null ? "NHIEM_VU" : kind;
    }
  }

  /** Người hoặc đơn vị phối hợp; {@code kind} là {@code PERSON} hoặc {@code UNIT}. */
  public record Assignee(String kind, UUID id) {

    public boolean person() {
      return "PERSON".equals(kind);
    }
  }

  /** Mốc kiểm soát. */
  public record Milestone(LocalDate date, String content) {}

  /** Cùng nội dung, khác người chủ trì (giao đồng loạt cho mỗi đơn vị). */
  public TaskDraftSpec withLead(UUID lead) {
    return new TaskDraftSpec(
        periodId,
        source,
        planItemId,
        catalogItemId,
        title,
        taskGroup,
        keyResultId,
        priority,
        lead,
        coAssignees,
        dueDate,
        acceptorId,
        milestones,
        productName,
        quantity,
        unit,
        evidenceRequired,
        qualityCriteria,
        sourceDocNumber,
        sourceDocTitle,
        basis,
        node);
  }

  /** Cùng nội dung, khác vị trí trong cây. */
  public TaskDraftSpec withNode(Node n) {
    return new TaskDraftSpec(
        periodId,
        source,
        planItemId,
        catalogItemId,
        title,
        taskGroup,
        keyResultId,
        priority,
        leadPersonId,
        coAssignees,
        dueDate,
        acceptorId,
        milestones,
        productName,
        quantity,
        unit,
        evidenceRequired,
        qualityCriteria,
        sourceDocNumber,
        sourceDocTitle,
        basis,
        n);
  }
}
