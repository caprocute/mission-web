/// Logic của SCR-0901 "Phiếu tự đánh giá" trên di động (QT-09 bước 3 đến 6): tải hồ sơ và biểu mẫu
/// Mẫu 1B, tiêu chí chung quy đổi bốn mức (BR-09-05), mức tự đề xuất, giải trình (BR-09-12), lưu nháp
/// với `If-Match`, xung đột 409 (MSG-09-07), nộp nhận 202 rồi chờ ký (MOB-07).
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../schema/compiled_form.dart' show FormDefinitionException;
import '../schema/form_definition.dart';
import '../widgets/form_controller.dart';
import 'appraisal_api.dart';
import 'appraisal_models.dart';
import 'appraisal_text.dart';
import 'platform_services.dart';

/// Khoá biểu mẫu động của phần tự đề xuất mức (Mẫu 1B), khoá trường trùng `AppraisalSelfAssessmentInput`.
const selfFormKey = 'mau-1b';

enum SelfAssessmentPhase {
  loading,

  /// Hồ sơ `CHUA_LAP`: kết quả đang được tính (MSG-09-01).
  notReady,
  editing,

  /// Đã gửi yêu cầu nộp (202), đang chờ người dùng ký số.
  awaitingSignature,

  /// Không tải được hồ sơ hoặc biểu mẫu.
  failed,
}

enum BannerKind { info, success, warning, error }

class SelfAssessmentBanner {
  const SelfAssessmentBanner(this.kind, this.text, {this.offerReload = false});
  final BannerKind kind;
  final String text;

  /// Hiện nút "Tải lại" (xung đột ghi).
  final bool offerReload;
}

class SelfAssessmentController extends ChangeNotifier {
  SelfAssessmentController({
    required this.api,
    required this.profileId,
    this.referenceRating,
    EncryptedDraftStore? drafts,
    this.autosaveInterval = const Duration(seconds: 30),
    String Function()? newKey,
  }) : drafts = drafts ?? InMemoryDraftStore(),
       _newKey = newKey ?? uuidV7;

  final AppraisalApi api;
  final String profileId;

  /// Mức hệ thống tham khảo (trường 8) lấy từ dòng hồ sơ của danh sách.
  final String? referenceRating;
  final EncryptedDraftStore drafts;

  /// Tự lưu nháp lên máy chủ (TL-02b SCR-0901 trường 12: 30 giây một lần); `null` là tắt.
  final Duration? autosaveInterval;
  final String Function() _newKey;

  var phase = SelfAssessmentPhase.loading;
  SelfAssessmentBanner? banner;
  AppraisalProfile? profile;
  String? etag;
  FormDefinition? definition;
  VtFormController? form;
  Map<String, bool> criteria = {};
  bool busy = false;

  /// `Location` của job ký số sau khi nộp (MOB-07 theo dõi).
  String? signJobLocation;

  bool _dirty = false;
  Timer? _autosave;
  ({String body, String key})? _pendingSave;
  ({String body, String key})? _pendingSubmit;

  bool get editable => phase == SelfAssessmentPhase.editing && (profile?.canSave ?? false) && !busy;

  /// Màn hình có trường C2 (luôn đúng với phiếu tự đánh giá): phải chặn chụp màn hình.
  bool get hasC2 => definition?.hasC2 ?? true;

  int get metCount => criteria.values.where((v) => v).length;
  String get criteriaLevelCode => criteriaLevel(metCount, criteria.length);

  String? get selfRating => form?.model['selfRatingCode'] as String?;

  Future<void> load() async {
    phase = SelfAssessmentPhase.loading;
    banner = null;
    notifyListeners();
    try {
      final tagged = await api.getProfile(profileId);
      final formJson = definition == null ? await api.getCurrentForm(selfFormKey) : null;
      profile = tagged.value;
      etag = tagged.etag ?? '"${tagged.value.rowVersion}"';
      if (formJson != null) definition = FormDefinition.fromUiFormVersion(formJson.value);
    } on ApiOffline {
      return _fail(AppraisalText.offlineLoad);
    } on ApiProblem catch (p) {
      return _fail(p.status == 404 ? AppraisalText.notFound : AppraisalText.loadFailed);
    } on FormDefinitionException {
      return _fail(AppraisalText.formBroken);
    }
    final p = profile!;
    if (p.status == 'CHUA_LAP') {
      phase = SelfAssessmentPhase.notReady;
      notifyListeners();
      return;
    }
    final sa = p.selfAssessment;
    var initial = <String, Object?>{
      'selfRatingCode': sa['selfRatingCode'] ?? referenceRating,
      'breakthrough': ?sa['breakthrough'],
      'reason': ?sa['reason'],
      'content': ?sa['content'],
    };
    criteria = {for (final c in p.criteria) c.code: c.met};
    final local = await drafts.read(profileId);
    if (local != null && local.baseRowVersion == p.rowVersion && p.canSave) {
      // bản nháp trên máy còn dựa trên đúng phiên bản máy chủ: dùng lại phần đã nhập ngoại tuyến
      initial = {
        for (final k in ['selfRatingCode', 'breakthrough', 'reason', 'content']) k: ?local.input[k],
      };
      for (final c in (local.input['criteria'] as List?) ?? const []) {
        final m = (c as Map).cast<String, Object?>();
        if (criteria.containsKey(m['criteriaCode'])) criteria['${m['criteriaCode']}'] = m['met'] == true;
      }
      _dirty = true;
    } else if (local != null) {
      await drafts.delete(profileId);
    }
    form?.removeListener(_formChanged);
    form = VtFormController(definition!, initial)..addListener(_formChanged);
    phase = SelfAssessmentPhase.editing;
    _startAutosave();
    notifyListeners();
  }

  void _fail(String text) {
    phase = SelfAssessmentPhase.failed;
    banner = SelfAssessmentBanner(BannerKind.error, text);
    notifyListeners();
  }

  void _startAutosave() {
    _autosave?.cancel();
    final every = autosaveInterval;
    if (every == null || !(profile?.canSave ?? false)) return;
    _autosave = Timer.periodic(every, (_) {
      if (_dirty && !busy && phase == SelfAssessmentPhase.editing) saveDraft(auto: true);
    });
  }

  void _formChanged() {
    _dirty = true;
    _storeLocal();
    notifyListeners();
  }

  void setCriterion(String code, bool met) {
    if (!editable || !criteria.containsKey(code)) return;
    criteria = {...criteria, code: met};
    _dirty = true;
    _storeLocal();
    notifyListeners();
  }

  void _storeLocal() {
    final p = profile;
    if (p == null) return;
    unawaited(drafts.write(profileId, LocalDraft(p.rowVersion, input())));
  }

  /// Thân `PUT .../self-assessment` (`AppraisalSelfAssessmentInput`); chuỗi đã bỏ khoảng trắng hai đầu.
  Map<String, Object?> input() {
    final v = form?.value ?? const {};
    String? text(String k) {
      final s = (v[k] as String?)?.trim();
      return s == null || s.isEmpty ? null : s;
    }

    return {
      'criteria': [
        for (final e in criteria.entries) {'criteriaCode': e.key, 'met': e.value},
      ],
      'selfRatingCode': ?v['selfRatingCode'],
      'breakthrough': ?text('breakthrough'),
      'breakthroughEvidenceIds': profile?.evidenceIds ?? const <String>[],
      'reason': ?text('reason'),
      'content': ?text('content'),
    };
  }

  /// Mức tự đề xuất khác mức tham khảo (MSG-09-02 cảnh báo, trường 11 bắt buộc).
  bool get differsFromReference => referenceRating != null && selfRating != null && selfRating != referenceRating;

  /// Quy tắc nền tảng của MOD-04 (BR-09-12 và điều kiện HTXSNV của SCR-0901), viết bằng mã ở máy
  /// chủ, không cấu hình được; chạy ở máy để chặn sớm. Máy chủ trả 422 nếu vẫn vi phạm.
  Map<String, String> platformErrors() {
    final i = input();
    final out = <String, String>{};
    if (differsFromReference) {
      final len = ((i['reason'] as String?) ?? '').runes.length;
      if (len < 20 || len > 2000) out['reason'] = AppraisalText.reasonRequired;
    }
    if (i['selfRatingCode'] == 'HTXSNV' &&
        ((i['breakthrough'] as String?) == null || (profile?.evidenceIds.isEmpty ?? true))) {
      out['breakthrough'] = AppraisalText.breakthroughRequired;
    }
    return out;
  }

  /// Đủ trường bắt buộc để bật "Ký số và nộp".
  bool get readyToSubmit =>
      editable &&
      (profile?.canSubmit ?? false) &&
      form != null &&
      form!.violations.isEmpty &&
      definition!.unsupportedFields.isEmpty &&
      selfRating != null &&
      platformErrors().isEmpty;

  /// Lưu nháp: không kiểm đủ trường bắt buộc (hợp đồng); trả `true` khi máy chủ đã nhận.
  Future<bool> saveDraft({bool auto = false}) async {
    final p = profile;
    if (p == null || !p.canSave || busy) return false;
    final body = jsonEncode(input());
    // lần thử lại cùng nội dung dùng lại khoá cũ; nội dung đổi là một ý định ghi mới
    final key = _pendingSave?.body == body ? _pendingSave!.key : _newKey();
    _pendingSave = (body: body, key: key);
    busy = true;
    notifyListeners();
    try {
      final saved = await api.saveSelfAssessment(
        profileId,
        (jsonDecode(body) as Map).cast<String, Object?>(),
        ifMatch: etag!,
        idempotencyKey: key,
      );
      _pendingSave = null;
      _applySaved(saved);
      if (!auto) banner = const SelfAssessmentBanner(BannerKind.success, AppraisalText.saved);
      return true;
    } on ApiOffline {
      banner = const SelfAssessmentBanner(BannerKind.warning, AppraisalText.offlineSaved);
      return false;
    } on ApiProblem catch (pr) {
      _pendingSave = null;
      _problem(pr);
      return false;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  void _applySaved(Tagged<AppraisalProfile> saved) {
    profile = saved.value;
    etag = saved.etag ?? '"${saved.value.rowVersion}"';
    _dirty = false;
    unawaited(drafts.write(profileId, LocalDraft(saved.value.rowVersion, input())));
  }

  void _problem(ApiProblem pr) {
    if (pr.status == 409 && pr.isVersionConflict) {
      _autosave?.cancel();
      banner = const SelfAssessmentBanner(BannerKind.error, AppraisalText.conflict, offerReload: true);
      return;
    }
    if (pr.status == 422) {
      final errors = {...pr.fieldErrors};
      if (pr.code == 'APPRAISAL-422-REASON-REQUIRED' || pr.code == 'APPRAISAL-422-SUGGESTED-REASON-REQUIRED') {
        errors['reason'] = AppraisalText.reasonRequired;
      }
      if (pr.code == 'APPRAISAL-422-BREAKTHROUGH-REQUIRED') errors['breakthrough'] = AppraisalText.breakthroughRequired;
      form?.serverErrors = errors;
      form?.showAllErrors();
      banner = const SelfAssessmentBanner(BannerKind.error, AppraisalText.fixErrors);
      return;
    }
    banner = SelfAssessmentBanner(BannerKind.error, switch (pr.status) {
      403 => AppraisalText.forbidden,
      409 => AppraisalText.invalidStatus,
      428 => AppraisalText.conflict,
      _ => AppraisalText.saveFailed,
    }, offerReload: pr.status == 409 || pr.status == 428);
  }

  /// "Ký số và nộp": lưu nội dung mới nhất rồi gửi yêu cầu nộp; 202 thì chuyển sang chờ ký.
  Future<bool> submit({String? signProvider}) async {
    form?.showAllErrors();
    final platform = platformErrors();
    form?.serverErrors = platform;
    if (!readyToSubmit) {
      banner = const SelfAssessmentBanner(BannerKind.error, AppraisalText.fixErrors);
      notifyListeners();
      return false;
    }
    if (_dirty || _pendingSave != null) {
      if (!await saveDraft(auto: true)) return false;
    }
    final body = '${profile!.rowVersion}|$signProvider';
    final key = _pendingSubmit?.body == body ? _pendingSubmit!.key : _newKey();
    _pendingSubmit = (body: body, key: key);
    busy = true;
    notifyListeners();
    try {
      final accepted = await api.submit(profileId, ifMatch: etag!, idempotencyKey: key, signProvider: signProvider);
      _pendingSubmit = null;
      signJobLocation = accepted.location;
      phase = SelfAssessmentPhase.awaitingSignature;
      _autosave?.cancel();
      // thẻ "Chờ ký số" của màn hình thay cho thông báo
      banner = null;
      await drafts.delete(profileId);
      return true;
    } on ApiOffline {
      banner = const SelfAssessmentBanner(BannerKind.warning, AppraisalText.offlineSubmit);
      return false;
    } on ApiProblem catch (pr) {
      _pendingSubmit = null;
      _problem(pr);
      return false;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  /// Tải lại hồ sơ sau xung đột; bỏ bản nháp trên máy vì đã lệch phiên bản.
  Future<void> reload() async {
    await drafts.delete(profileId);
    _pendingSave = null;
    _pendingSubmit = null;
    await load();
  }

  @override
  void dispose() {
    _autosave?.cancel();
    form?.removeListener(_formChanged);
    form?.dispose();
    super.dispose();
  }
}
