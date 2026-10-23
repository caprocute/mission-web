/// Mô hình dữ liệu tối thiểu của hồ sơ đánh giá cho SCR-0901 (`AppraisalProfileRow`,
/// `AppraisalProfile`, `GeneralCriteriaResult`, `AppraisalSelfAssessment` của appraisals.yaml), cùng
/// nhãn nghiệp vụ dùng chung với web. Mã trạng thái chỉ dùng trong mã, giao diện hiện nhãn.
library;

Map<String, Object?> _m(Object? v) => v is Map ? v.cast<String, Object?>() : const {};

/// Nhãn mức xếp loại (baseline mục 9.1), thứ tự từ cao xuống thấp.
const ratingLevels = ['HTXSNV', 'HTTNV', 'HTNV', 'KHTNV'];
const ratingLabel = {
  'HTXSNV': 'Hoàn thành xuất sắc nhiệm vụ',
  'HTTNV': 'Hoàn thành tốt nhiệm vụ',
  'HTNV': 'Hoàn thành nhiệm vụ',
  'KHTNV': 'Không hoàn thành nhiệm vụ',
};

/// Nhãn mức tiêu chí chung (BR-09-05).
const criteriaLevelLabel = {
  'THUC_HIEN_TOT': 'Thực hiện tốt',
  'DAM_BAO': 'Đảm bảo',
  'CO_BAN_DAM_BAO': 'Cơ bản đảm bảo',
  'KHONG_DAM_BAO': 'Không đảm bảo',
};

const flowLabel = {'DANG': 'Luồng Đảng', 'NHA_NUOC': 'Luồng Nhà nước'};

/// Nhãn trạng thái hồ sơ đánh giá (baseline mục 9.1, TL-02 QT-09).
const profileStatusLabel = {
  'CHUA_LAP': 'Chưa lập',
  'TU_DANH_GIA': 'Đang tự đánh giá',
  'CHO_THAM_DINH': 'Chờ thẩm định',
  'DA_THAM_DINH': 'Đã thẩm định',
  'CHO_QUYET_DINH': 'Chờ quyết định',
  'DA_QUYET_DINH': 'Đã quyết định',
  'DA_KY_SO': 'Đã ký số',
  'DA_KHOA': 'Đã khoá',
  'KHIEU_NAI': 'Đang khiếu nại',
};

/// BR-09-05: tỷ lệ đáp ứng 100% là Thực hiện tốt, từ 90% đến dưới 100% Đảm bảo, từ 60% đến dưới 90%
/// Cơ bản đảm bảo, dưới 60% Không đảm bảo. Chỉ để hiển thị; máy chủ tính lại khi lưu.
String criteriaLevel(int met, int total) {
  final r = total == 0 ? 0.0 : met / total;
  if (r >= 1) return 'THUC_HIEN_TOT';
  if (r >= 0.9) return 'DAM_BAO';
  if (r >= 0.6) return 'CO_BAN_DAM_BAO';
  return 'KHONG_DAM_BAO';
}

/// `2027-01-05` thành `05/01/2027`.
String dmy(String? iso) {
  final m = iso == null ? null : RegExp(r'^(\d{4})-(\d{2})-(\d{2})').firstMatch(iso);
  return m == null ? '' : '${m[3]}/${m[2]}/${m[1]}';
}

class AppraisalProfileRow {
  AppraisalProfileRow({
    required this.id,
    required this.periodLabel,
    required this.flow,
    required this.status,
    required this.rowVersion,
    this.autoRatingCode,
    this.totalScore,
    this.dueDate,
    this.permittedActions = const {},
  });

  factory AppraisalProfileRow.fromJson(Map<Object?, Object?> raw) {
    final j = raw.cast<String, Object?>();
    return AppraisalProfileRow(
      id: j['id'] as String,
      periodLabel: '${_m(j['period'])['label'] ?? ''}',
      flow: j['flow'] as String? ?? 'DANG',
      status: j['status'] as String? ?? 'CHUA_LAP',
      rowVersion: (j['rowVersion'] as num?)?.toInt() ?? 0,
      autoRatingCode: j['autoRatingCode'] as String?,
      totalScore: (j['totalScore'] as num?)?.toDouble(),
      dueDate: j['dueDate'] as String?,
      permittedActions: {for (final a in (j['permittedActions'] as List?) ?? const []) '$a'},
    );
  }

  final String id;
  final String periodLabel;
  final String flow;
  final String status;
  final int rowVersion;

  /// Mức hệ thống tham khảo (trường 8 của SCR-0901).
  final String? autoRatingCode;
  final double? totalScore;
  final String? dueDate;
  final Set<String> permittedActions;
}

class GeneralCriteriaItem {
  const GeneralCriteriaItem(this.code, this.label, this.met);
  final String code;
  final String label;
  final bool met;
}

class AppraisalProfile {
  AppraisalProfile({
    required this.id,
    required this.periodLabel,
    required this.flow,
    required this.status,
    required this.rowVersion,
    this.criteria = const [],
    this.selfAssessment = const {},
    this.selfAssessDue,
    this.workingDaysLeft,
    this.permittedActions,
  });

  factory AppraisalProfile.fromJson(Map<String, Object?> j) {
    final results = [for (final g in (j['generalCriteria'] as List?) ?? const []) _m(g)];
    // tiêu chí chung của bước tự đánh giá; chưa có thì lấy kết quả đầu tiên làm danh mục
    final self = results.where((g) => g['stage'] == 'TU_DANH_GIA').firstOrNull ?? results.firstOrNull;
    return AppraisalProfile(
      id: j['id'] as String,
      periodLabel: '${_m(j['period'])['label'] ?? ''}',
      flow: j['flow'] as String? ?? 'DANG',
      status: j['status'] as String? ?? 'CHUA_LAP',
      rowVersion: (j['rowVersion'] as num?)?.toInt() ?? 0,
      criteria: [
        for (final it in (self?['items'] as List?) ?? const [])
          GeneralCriteriaItem(
            '${_m(it)['criteriaCode']}',
            '${_m(it)['label'] ?? _m(it)['criteriaCode']}',
            _m(it)['met'] == true,
          ),
      ],
      selfAssessment: _m(j['selfAssessment']),
      selfAssessDue: j['selfAssessDue'] as String?,
      workingDaysLeft: (j['workingDaysLeft'] as num?)?.toInt(),
      permittedActions: j['permittedActions'] is List ? {for (final a in j['permittedActions'] as List) '$a'} : null,
    );
  }

  final String id;
  final String periodLabel;
  final String flow;
  final String status;
  final int rowVersion;
  final List<GeneralCriteriaItem> criteria;

  /// Phiếu tự đánh giá hiện hành (`AppraisalSelfAssessment`); có trường C2.
  final Map<String, Object?> selfAssessment;
  final String? selfAssessDue;
  final int? workingDaysLeft;

  /// `null` khi máy chủ không gửi: khi đó chỉ xét trạng thái.
  final Set<String>? permittedActions;

  /// Chủ hồ sơ được ghi khi hồ sơ ở `TU_DANH_GIA` (SCR-0901 "Quyền").
  bool get canSave => status == 'TU_DANH_GIA' && (permittedActions?.contains('SCR-0901.luu') ?? true);
  bool get canSubmit => status == 'TU_DANH_GIA' && (permittedActions?.contains('SCR-0901.ky-nop') ?? true);

  List<String> get evidenceIds => [
    for (final e in (selfAssessment['breakthroughEvidenceIds'] as List?) ?? const []) '$e',
  ];
}
