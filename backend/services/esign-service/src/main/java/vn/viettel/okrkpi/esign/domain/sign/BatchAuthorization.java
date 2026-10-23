package vn.viettel.okrkpi.esign.domain.sign;

/**
 * Cách người ký xác nhận một lần cho cả lô ({@code esign.sign_request.batch_authorization},
 * DV-10-06, ADR-31): {@code SAD} là phiên ủy quyền ký nhiều băm của nhà cung cấp ký từ xa; {@code
 * STEP_UP} là lần xác thực nâng cao của người quyết định khi ký bằng HSM của tổ chức.
 */
public enum BatchAuthorization {
  SAD,
  STEP_UP
}
