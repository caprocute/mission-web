package vn.viettel.okrkpi.dossier.application.specialcase.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng {@code truong_hop_dac_thu} của Policy Pack áp cho kỳ (BR-11-05). MOD-06 không được phụ thuộc
 * {@code policy :: api} (TL-01 ma trận phụ thuộc module) nên bảng đi qua cổng này; bean mặc định
 * đọc gói qua {@code config :: api} ({@code SpecialCaseRulesPort}), thiếu thì dùng bảng gói khối
 * Đảng v2.0.
 */
public interface SpecialCaseRuleSource {

  List<SpecialCaseRule> rules(TenantId tenant, UUID periodId);
}
