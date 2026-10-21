package vn.viettel.okrkpi.org.infrastructure.web;

import java.util.ArrayList;
import java.util.List;
import vn.viettel.okrkpi.org.application.ChangeCommand;
import vn.viettel.okrkpi.org.application.ChangeResult;
import vn.viettel.okrkpi.org.application.OrgUnitHistoryItem;
import vn.viettel.okrkpi.org.application.OrgUnitView;
import vn.viettel.okrkpi.org.application.Page;
import vn.viettel.okrkpi.org.application.SearchHit;
import vn.viettel.okrkpi.org.application.SubjectGroupRecord;
import vn.viettel.okrkpi.org.application.TreeView;
import vn.viettel.okrkpi.org.application.UnitCommand;
import vn.viettel.okrkpi.org.application.UnitRef;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.PersonAssignmentRow;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.PersonSummaryRow;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.UnitAssignmentRow;
import vn.viettel.okrkpi.org.domain.OrgChangeType;
import vn.viettel.okrkpi.org.domain.OrgUnitContent;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.infrastructure.web.model.Assignment;
import vn.viettel.okrkpi.org.infrastructure.web.model.AssignmentKind;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeOrder;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgPerson;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTree;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTreeNode;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnit;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitAssignment;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitInput;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitRef;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitSearchHit;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitSearchHitAncestorsInner;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitType;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitVersionChangeOrder;
import vn.viettel.okrkpi.org.infrastructure.web.model.SubjectGroup;

/** Ánh xạ giữa DTO sinh từ hợp đồng và mô hình ứng dụng. */
final class OrgWebMapper {

  private OrgWebMapper() {
  }

  static UnitCommand toCommand(OrgUnitInput in) {
    List<String> flows = in.getFlows().stream().map(OrgUnitInput.FlowsEnum::getValue).toList();
    OrgUnitContent content = new OrgUnitContent(in.getCode(), in.getName(), in.getShortName(),
        in.getUnitType().getValue(), in.getSubjectGroupId(), true, flows, in.getLevel());
    return new UnitCommand(content, in.getParentId(), in.getValidFrom(), in.getValidTo());
  }

  static ChangeCommand toCommand(OrgChangeRequest in) {
    List<UnitCommand> newUnits = in.getNewUnits() == null ? List.of()
        : in.getNewUnits().stream().map(OrgWebMapper::toCommand).toList();
    return new ChangeCommand(OrgChangeType.valueOf(in.getChangeType().getValue()), in.getEffectiveOn(),
        in.getOrderNo(), in.getOrderDate(), in.getSignedDocRef(), in.getNewName(), in.getRelatedUnitIds(), newUnits,
        in.getMoveAssignmentsTo());
  }

  static OrgUnit toDto(OrgUnitView view) {
    OrgUnitVersion v = view.version();
    OrgUnitContent c = v.content();
    OrgUnit dto = new OrgUnit();
    dto.setId(v.id());
    dto.setVersionId(v.versionId());
    dto.setCode(c.code());
    dto.setName(c.name());
    dto.setShortName(c.shortName());
    dto.setLevel(c.level());
    dto.setUnitType(OrgUnitType.fromValue(c.unitType()));
    dto.setSubjectGroupId(c.subjectGroupId());
    dto.setSubjectGroupName(view.subjectGroupName());
    dto.setFlows(c.flows().stream().map(OrgUnit.FlowsEnum::fromValue).toList());
    dto.setParentId(view.parentId());
    dto.setParentName(view.parentName());
    dto.setValidFrom(v.validPeriod().from());
    dto.setValidTo(v.validPeriod().to());
    dto.setPredecessorIds(new ArrayList<>(view.predecessorIds()));
    dto.setRecordedFrom(v.recordedFrom());
    dto.setRecordedByName(view.recordedByName());
    dto.setAssignmentCount(view.assignmentCount());
    dto.setRowVersion(view.rowVersion());
    return dto;
  }

  static vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitVersion toDto(OrgUnitHistoryItem item) {
    OrgUnitVersion v = item.version();
    var dto = new vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitVersion();
    dto.setVersionId(v.versionId());
    dto.setCode(v.content().code());
    dto.setName(v.content().name());
    dto.setParentName(item.parentName());
    dto.setSubjectGroupName(item.subjectGroupName());
    dto.setValidFrom(v.validPeriod().from());
    dto.setValidTo(v.validPeriod().to());
    dto.setRecordedFrom(v.recordedFrom());
    dto.setRecordedTo(item.recordedTo());
    dto.setRecordedByName(item.recordedByName());
    if (item.changeType() != null) {
      dto.setChangeType(vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeType.fromValue(item.changeType().name()));
    }
    if (item.changeOrder() != null) {
      OrgUnitVersionChangeOrder order = new OrgUnitVersionChangeOrder();
      order.setId(item.changeOrder().id());
      order.setOrderNo(item.changeOrder().orderNo());
      order.setOrderDate(item.changeOrder().orderDate());
      order.setSignedDocRef(item.changeOrder().signedDocRef());
      dto.setChangeOrder(order);
    }
    dto.setChangedFields(new ArrayList<>(item.changedFields()));
    return dto;
  }

  static OrgTree toDto(TreeView view) {
    OrgTree tree = new OrgTree();
    tree.setAsOf(view.asOf());
    tree.setRecordedAsOf(view.recordedAsOf());
    tree.setReadOnly(view.readOnly());
    tree.setRoots(view.roots().stream().map(OrgWebMapper::toDto).toList());
    return tree;
  }

  private static OrgTreeNode toDto(TreeView.Node node) {
    OrgTreeNode dto = new OrgTreeNode();
    dto.setId(node.id());
    dto.setCode(node.code());
    dto.setName(node.name());
    dto.setUnitType(OrgUnitType.fromValue(node.unitType()));
    dto.setHasChildren(node.hasChildren());
    dto.setChildren(node.children().stream().map(OrgWebMapper::toDto).toList());
    return dto;
  }

  static OrgUnitSearchHit toDto(SearchHit hit) {
    OrgUnitSearchHit dto = new OrgUnitSearchHit();
    dto.setId(hit.id());
    dto.setCode(hit.code());
    dto.setName(hit.name());
    dto.setUnitType(OrgUnitType.fromValue(hit.unitType()));
    dto.setAncestors(hit.ancestors().stream().map(a -> {
      OrgUnitSearchHitAncestorsInner inner = new OrgUnitSearchHitAncestorsInner();
      inner.setId(a.id());
      inner.setCode(a.code());
      inner.setName(a.name());
      return inner;
    }).toList());
    return dto;
  }

  static OrgUnitRef toDto(UnitRef ref) {
    OrgUnitRef dto = new OrgUnitRef();
    dto.setId(ref.id());
    dto.setName(ref.name());
    dto.setParentId(ref.parentId());
    return dto;
  }

  static SubjectGroup toDto(SubjectGroupRecord g) {
    SubjectGroup dto = new SubjectGroup();
    dto.setId(g.id());
    dto.setCode(g.code());
    dto.setName(g.name());
    dto.setFlow(SubjectGroup.FlowEnum.fromValue(g.flow()));
    dto.setDescription(g.description());
    dto.setValidFrom(g.validFrom());
    dto.setValidTo(g.validTo());
    return dto;
  }

  static OrgChangeOrderResponse toDto(ChangeResult r) {
    OrgChangeOrderResponse dto = new OrgChangeOrderResponse();
    dto.setId(r.orderId());
    dto.setChangeType(vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeType.fromValue(r.changeType().name()));
    dto.setOrderNo(r.orderNo());
    dto.setEffectiveOn(r.effectiveOn());
    dto.setStatus(OrgChangeOrder.StatusEnum.fromValue(r.status()));
    dto.setAppliedAt(r.appliedAt());
    dto.setAffectedUnitIds(new ArrayList<>(r.affectedUnitIds()));
    dto.setCreatedUnitIds(new ArrayList<>(r.createdUnitIds()));
    dto.setMovedAssignmentCount(r.movedAssignments());
    if (r.lockedPeriodLabel() != null) {
      dto.setWarnings(List.of(new OrgChangeOrderResponse.Warning("MSG-01-10", r.lockedPeriodLabel(),
          "Thay đổi có hiệu lực trước ngày khoá của kỳ " + r.lockedPeriodLabel()
              + ". Kết quả kỳ đã khoá sẽ không tự tính lại; nếu cần, đề nghị mở lại kỳ.")));
    }
    return dto;
  }

  static OrgPerson toDto(PersonSummaryRow item) {
    OrgPerson dto = new OrgPerson();
    dto.setId(item.personId());
    dto.setName(item.fullName());
    dto.setTitle(item.jobTitleName());
    dto.setUnitId(item.unitId());
    dto.setUnitName(item.unitName());
    return dto;
  }

  static Assignment toDto(PersonAssignmentRow item) {
    Assignment dto = new Assignment();
    dto.setId(item.id().toString());
    dto.setOrgUnitId(item.orgUnitId().toString());
    dto.setOrgUnitName(item.orgUnitName());
    dto.setJobTitleName(item.jobTitleName());
    dto.setKind(AssignmentKind.fromValue(item.kind()));
    dto.setBranch(Assignment.BranchEnum.fromValue(item.branch()));
    dto.setRoles(new ArrayList<>());
    dto.setValidFrom(item.validFrom());
    dto.setValidTo(item.validTo());
    return dto;
  }

  static OrgUnitAssignment toDto(UnitAssignmentRow item) {
    OrgUnitAssignment dto = new OrgUnitAssignment();
    dto.setId(item.id());
    dto.setPersonId(item.personId());
    dto.setPersonName(item.personName());
    dto.setJobTitleName(item.jobTitleName());
    dto.setKind(AssignmentKind.fromValue(item.kind()));
    dto.setBranch(OrgUnitAssignment.BranchEnum.fromValue(item.branch()));
    dto.setValidFrom(item.validFrom());
    dto.setValidTo(item.validTo());
    return dto;
  }
}
