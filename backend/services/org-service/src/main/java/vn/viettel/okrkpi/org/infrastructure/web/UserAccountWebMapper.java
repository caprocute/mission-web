package vn.viettel.okrkpi.org.infrastructure.web;

import vn.viettel.okrkpi.org.application.AssignmentAdminService.AssignmentView;
import vn.viettel.okrkpi.org.application.UserAccountService.AccountPage;
import vn.viettel.okrkpi.org.application.UserAccountService.Created;
import vn.viettel.okrkpi.org.application.UserAccountService.UserAccountView;
import vn.viettel.okrkpi.org.application.port.UserAccountStore.JobTitleRow;
import vn.viettel.okrkpi.org.infrastructure.web.model.AssignmentKind;
import vn.viettel.okrkpi.org.infrastructure.web.model.JobTitle;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitAssignment;
import vn.viettel.okrkpi.org.infrastructure.web.model.UserAccount;
import vn.viettel.okrkpi.org.infrastructure.web.model.UserAccountCounts;
import vn.viettel.okrkpi.org.infrastructure.web.model.UserAccountCreated;
import vn.viettel.okrkpi.org.infrastructure.web.model.UserAccountPage;
import vn.viettel.okrkpi.org.infrastructure.web.model.UserAccountStatus;

/** DV-04-09 · Ánh xạ quản trị tài khoản và phân công sang mô hình sinh từ {@code org.yaml}. */
final class UserAccountWebMapper {

  private UserAccountWebMapper() {
  }

  static UserAccount toDto(UserAccountView v) {
    UserAccount dto = new UserAccount();
    dto.setId(v.id());
    dto.setUsername(v.username());
    dto.setFullName(v.fullName());
    dto.setEmail(v.email());
    dto.setStatus(UserAccountStatus.fromValue(v.status()));
    dto.setPrimaryUnitId(v.primaryUnitId());
    dto.setPrimaryUnitName(v.primaryUnitName());
    dto.setJobTitleName(v.jobTitleName());
    dto.setConcurrentCount(v.concurrentCount());
    dto.setLockedAt(v.lockedAt());
    dto.setLockReason(v.lockReason());
    dto.setPasswordResetAt(v.passwordResetAt());
    dto.setCreatedAt(v.createdAt());
    dto.setSource(v.source() == null ? null : UserAccount.SourceEnum.fromValue(v.source()));
    dto.setRowVersion(v.rowVersion());
    dto.setPermittedActions(new java.util.LinkedHashSet<>(v.permittedActions()));
    return dto;
  }

  static UserAccountCreated toDto(Created c) {
    UserAccountView v = c.account();
    UserAccountCreated dto = new UserAccountCreated();
    dto.setId(v.id());
    dto.setUsername(v.username());
    dto.setFullName(v.fullName());
    dto.setEmail(v.email());
    dto.setStatus(UserAccountStatus.fromValue(v.status()));
    dto.setPrimaryUnitId(v.primaryUnitId());
    dto.setPrimaryUnitName(v.primaryUnitName());
    dto.setJobTitleName(v.jobTitleName());
    dto.setConcurrentCount(v.concurrentCount());
    dto.setCreatedAt(v.createdAt());
    dto.setSource(v.source() == null ? null : UserAccountCreated.SourceEnum.fromValue(v.source()));
    dto.setRowVersion(v.rowVersion());
    dto.setPermittedActions(new java.util.LinkedHashSet<>(v.permittedActions()));
    dto.setTemporaryPassword(c.temporaryPassword());
    return dto;
  }

  static UserAccountPage toDto(AccountPage page) {
    UserAccountPage dto = new UserAccountPage();
    dto.setItems(page.items().stream().map(UserAccountWebMapper::toDto).toList());
    dto.setNextCursor(page.nextCursor());
    UserAccountCounts counts = new UserAccountCounts();
    counts.setTotal(page.counts().total());
    counts.setActive(page.counts().active());
    counts.setLocked(page.counts().locked());
    dto.setCounts(counts);
    return dto;
  }

  static JobTitle toDto(JobTitleRow r) {
    JobTitle dto = new JobTitle();
    dto.setId(r.id());
    dto.setCode(r.code());
    dto.setName(r.name());
    dto.setLevel(r.level());
    return dto;
  }

  static OrgUnitAssignment toDto(AssignmentView v) {
    OrgUnitAssignment dto = new OrgUnitAssignment();
    dto.setId(v.id());
    dto.setPersonId(v.personId());
    dto.setPersonName(v.personName());
    dto.setJobTitleName(v.jobTitleName());
    dto.setKind(AssignmentKind.fromValue(v.kind()));
    dto.setBranch(OrgUnitAssignment.BranchEnum.fromValue(v.branch()));
    dto.setValidFrom(v.validFrom());
    dto.setValidTo(v.validTo());
    return dto;
  }
}
