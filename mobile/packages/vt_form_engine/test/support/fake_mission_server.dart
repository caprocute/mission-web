import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Máy chủ giả lập năm thao tác của SCR-0901 theo hợp đồng (YC-PT-13): kiểm `If-Match`,
/// `Idempotency-Key`, trả 409 khi lệch phiên bản, 202 kèm `Location` khi nộp. Dữ liệu giả.
class FakeMissionServer {
  FakeMissionServer({this.reference = 'HTTNV', this.evidence = const []});

  final String reference;
  final List<String> evidence;
  int rowVersion = 3;
  String status = 'TU_DANH_GIA';
  Map<String, Object?> selfAssessment = {'versionNo': 1};
  final requests = <http.Request>[];
  final idempotency = <String, http.Response>{};
  bool offline = false;

  static const id = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a01';

  /// Lược đồ Mẫu 1B theo quy ước của bộ ca BR-18-05 (biến `form`, `self`).
  static Map<String, Object?> mau1b() => {
    'formKey': 'mau-1b',
    'version': '3',
    'status': 'DA_PHAT_HANH',
    'schemaJson': {
      'type': 'object',
      'required': ['selfRatingCode', 'content'],
      'properties': {
        'selfRatingCode': {
          'type': 'string',
          'title': 'Mức tự đề xuất',
          'oneOf': [
            {'const': 'HTXSNV', 'title': 'Hoàn thành xuất sắc nhiệm vụ'},
            {'const': 'HTTNV', 'title': 'Hoàn thành tốt nhiệm vụ'},
            {'const': 'HTNV', 'title': 'Hoàn thành nhiệm vụ'},
            {'const': 'KHTNV', 'title': 'Không hoàn thành nhiệm vụ'},
          ],
          'x-classification': 'C1',
        },
        'breakthrough': {'type': 'string', 'title': 'Giải pháp đột phá', 'maxLength': 2000, 'x-classification': 'C2'},
        'reason': {
          'type': 'string',
          'title': 'Giải trình khi khác mức tham khảo',
          'maxLength': 2000,
          'x-classification': 'C2',
        },
        'content': {
          'type': 'string',
          'title': 'Ưu điểm, hạn chế, hướng khắc phục',
          'maxLength': 2000,
          'x-classification': 'C2',
        },
      },
    },
    'uiJson': {
      'widgets': {'breakthrough': 'vt.input.textarea', 'reason': 'vt.input.textarea', 'content': 'vt.input.textarea'},
      'breakthrough': {'hideExpression': 'form.selfRatingCode != "HTXSNV"'},
    },
    'celRules': [
      {'field': 'content', 'expr': 'size(form.content) >= 10', 'message': 'Cần ít nhất 10 ký tự'},
    ],
    'rowVersion': 3,
  };

  Map<String, Object?> profile() => {
    'id': id,
    'person': {'id': 'p1', 'name': 'Cán bộ thử'},
    'orgUnit': {'id': 'u1', 'name': 'Đơn vị thử'},
    'period': {'id': 'k1', 'label': 'Quý IV/2026', 'startDate': '2026-10-01', 'endDate': '2026-12-31'},
    'periodKind': 'QUY',
    'flow': 'DANG',
    'availableFlows': ['DANG'],
    'status': status,
    'selfAssessDue': '2027-01-06',
    'workingDaysLeft': 3,
    'generalCriteria': [
      {
        'stage': 'TU_DANH_GIA',
        'complianceRatio': 1,
        'level': 'THUC_HIEN_TOT',
        'items': [
          for (var i = 1; i <= 9; i++) {'criteriaCode': 'TCC-0$i', 'label': 'Tiêu chí chung số $i', 'met': true},
        ],
      },
    ],
    'selfAssessment': {...selfAssessment, 'breakthroughEvidenceIds': evidence},
    'ratingTrail': [],
    'rowVersion': rowVersion,
    'permittedActions': ['SCR-0901.luu', 'SCR-0901.ky-nop'],
  };

  Map<String, Object?> row() => {
    'id': id,
    'person': {'id': 'p1', 'name': 'Cán bộ thử'},
    'orgUnit': {'id': 'u1', 'name': 'Đơn vị thử'},
    'period': {'id': 'k1', 'label': 'Quý IV/2026', 'startDate': '2026-10-01', 'endDate': '2026-12-31'},
    'periodKind': 'QUY',
    'flow': 'DANG',
    'status': status,
    'autoRatingCode': reference,
    'levels': {'selfProposed': null, 'appraised': null, 'proposed': null, 'decided': null},
    'dueDate': '2027-01-06',
    'rowVersion': rowVersion,
  };

  static http.Response _json(Object body, int status, [Map<String, String> h = const {}]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json', ...h});

  static http.Response _problem(int status, String code, [List<Map<String, String>> errors = const []]) => _json({
    'type': 'https://okrkpi.vn/problems/x',
    'title': 'Lỗi',
    'status': status,
    'code': code,
    'errors': errors,
  }, status);

  late final http.Client client = MockClient((req) async {
    requests.add(req);
    if (offline) throw http.ClientException('mất mạng');
    final path = req.url.path;
    if (req.method == 'GET' && path == '/api/v1/appraisals/profiles/mine') {
      return _json({
        'items': [row()],
        'nextCursor': null,
      }, 200);
    }
    if (req.method == 'GET' && path == '/api/v1/appraisals/profiles/$id') {
      return _json(profile(), 200, {'etag': '"$rowVersion"'});
    }
    if (req.method == 'GET' && path == '/api/v1/ui/forms/mau-1b/current') {
      return _json(mau1b(), 200, {'etag': '"3"'});
    }
    final key = req.headers['Idempotency-Key'];
    if (req.method != 'GET' && key == null) return _problem(400, 'COMMON-400-IDEMPOTENCY-KEY');
    if (idempotency.containsKey('${req.method} $path $key')) return idempotency['${req.method} $path $key']!;
    final ifMatch = req.headers['If-Match'];
    if (ifMatch == null) return _problem(428, 'COMMON-428-PRECONDITION-REQUIRED');
    if (ifMatch != '"$rowVersion"') return _problem(409, 'APPRAISAL-409-VERSION-CONFLICT');
    late http.Response res;
    if (req.method == 'PUT' && path == '/api/v1/appraisals/profiles/$id/self-assessment') {
      final body = (jsonDecode(req.body) as Map).cast<String, Object?>();
      selfAssessment = {
        'versionNo': 1,
        for (final k in ['selfRatingCode', 'breakthrough', 'reason', 'content']) k: ?body[k],
      };
      rowVersion++;
      res = _json(profile(), 200, {'etag': '"$rowVersion"'});
    } else if (req.method == 'POST' && path == '/api/v1/appraisals/profiles/$id/submit') {
      final sa = selfAssessment;
      if (sa['selfRatingCode'] != reference && ((sa['reason'] as String?) ?? '').length < 20) {
        res = _problem(422, 'APPRAISAL-422-REASON-REQUIRED');
      } else {
        res = _json(
          {'id': 'job-1', 'type': 'ESIGN', 'status': 'DANG_CHAY', 'progress': 0},
          202,
          {'location': '/api/v1/jobs/job-1'},
        );
      }
    } else {
      res = _problem(404, 'COMMON-404');
    }
    idempotency['${req.method} $path $key'] = res;
    return res;
  });
}
