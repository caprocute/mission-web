/**
 * Bản sao tổ chức {@code org_replica} (CORE-00-03, TL-03 "Event-carried State Transfer"): gói kỹ
 * thuật ngoài tám module, chỉ ghi qua {@code orgReplicaDataSource} (tài khoản {@code
 * svc_org_replica}); tám module đọc qua {@link vn.viettel.okrkpi.replica.api.OrgDirectoryApi}.
 */
@ApplicationModule(
    displayName = "Bản sao tổ chức",
    allowedDependencies = {"shared", "config :: api"})
package vn.viettel.okrkpi.replica;

import org.springframework.modulith.ApplicationModule;
