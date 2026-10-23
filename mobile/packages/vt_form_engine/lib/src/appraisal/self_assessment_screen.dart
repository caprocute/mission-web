import 'package:flutter/material.dart';

import '../widgets/vt_dynamic_form.dart';
import 'appraisal_api.dart';
import 'appraisal_models.dart';
import 'appraisal_text.dart';
import 'platform_services.dart';
import 'self_assessment_controller.dart';

/// Danh sách hồ sơ đánh giá của tôi (đường vào SCR-0901, `listMyAppraisalProfiles`).
class MyAppraisalsScreen extends StatefulWidget {
  const MyAppraisalsScreen({super.key, required this.api, required this.onOpen, this.periodId});

  final AppraisalApi api;
  final String? periodId;
  final ValueChanged<AppraisalProfileRow> onOpen;

  @override
  State<MyAppraisalsScreen> createState() => _MyAppraisalsScreenState();
}

class _MyAppraisalsScreenState extends State<MyAppraisalsScreen> {
  late Future<List<AppraisalProfileRow>> _rows = _load();

  Future<List<AppraisalProfileRow>> _load() => widget.api.listMine(periodId: widget.periodId);

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text(AppraisalText.myProfilesTitle)),
    body: FutureBuilder(
      future: _rows,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snap.hasError) {
          return _Retry(
            text: snap.error is ApiOffline ? AppraisalText.offlineLoad : AppraisalText.loadFailed,
            onRetry: () => setState(() => _rows = _load()),
          );
        }
        final rows = snap.data!;
        if (rows.isEmpty) return const Center(child: Text(AppraisalText.noProfiles));
        return ListView.separated(
          itemCount: rows.length,
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, i) {
            final r = rows[i];
            return ListTile(
              key: ValueKey('profile-${r.id}'),
              minVerticalPadding: 12,
              title: Text('${r.periodLabel} · ${flowLabel[r.flow] ?? ''}'),
              subtitle: Text(
                [profileStatusLabel[r.status] ?? '', if (r.dueDate != null) 'Hạn nộp ${dmy(r.dueDate)}'].join(' · '),
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => widget.onOpen(r),
            );
          },
        );
      },
    ),
  );
}

/// SCR-0901 "Phiếu tự đánh giá" trên di động, một cột theo thứ tự công việc: tiêu chí chung, mức
/// tham khảo và tự đề xuất (biểu mẫu động Mẫu 1B), rồi lưu nháp hoặc ký số và nộp. Màn hình có dữ
/// liệu C2 nên bật chặn chụp màn hình trong suốt thời gian mở.
class SelfAssessmentScreen extends StatefulWidget {
  const SelfAssessmentScreen({
    super.key,
    required this.controller,
    this.screenSecurity = const NoopScreenSecurity(),
    this.onAwaitingSignature,
  });

  final SelfAssessmentController controller;
  final ScreenSecurity screenSecurity;

  /// Gọi khi đã nộp (202) để chuyển sang luồng ký số của MOB-07, với `Location` của job ký.
  final ValueChanged<String?>? onAwaitingSignature;

  @override
  State<SelfAssessmentScreen> createState() => _SelfAssessmentScreenState();
}

class _SelfAssessmentScreenState extends State<SelfAssessmentScreen> {
  SelfAssessmentController get c => widget.controller;

  @override
  void initState() {
    super.initState();
    widget.screenSecurity.protect();
    c.addListener(_changed);
    if (c.phase == SelfAssessmentPhase.loading) c.load();
  }

  @override
  void dispose() {
    c.removeListener(_changed);
    widget.screenSecurity.release();
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  Future<void> _submit() async {
    if (await c.submit()) widget.onAwaitingSignature?.call(c.signJobLocation);
  }

  @override
  Widget build(BuildContext context) {
    final p = c.profile;
    return Scaffold(
      appBar: AppBar(title: const Text(AppraisalText.selfAssessmentTitle)),
      body: switch (c.phase) {
        SelfAssessmentPhase.loading => const Center(child: CircularProgressIndicator()),
        SelfAssessmentPhase.failed => _Retry(text: c.banner?.text ?? AppraisalText.loadFailed, onRetry: c.load),
        SelfAssessmentPhase.notReady => const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(AppraisalText.notReady, textAlign: TextAlign.center),
          ),
        ),
        _ => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text('${p!.periodLabel} · ${flowLabel[p.flow] ?? ''}', style: Theme.of(context).textTheme.titleMedium),
            Text(profileStatusLabel[p.status] ?? ''),
            if (p.selfAssessDue != null) Text(AppraisalText.due(dmy(p.selfAssessDue), p.workingDaysLeft)),
            const SizedBox(height: 12),
            if (c.banner != null) _BannerView(banner: c.banner!, onReload: c.reload),
            if (c.phase == SelfAssessmentPhase.awaitingSignature)
              const _AwaitingSignature()
            else ...[
              _criteria(context),
              const SizedBox(height: 16),
              _rating(context),
              const SizedBox(height: 16),
              _actions(),
            ],
          ],
        ),
      },
    );
  }

  Widget _criteria(BuildContext context) {
    final t = Theme.of(context).textTheme;
    final level = criteriaLevelLabel[c.criteriaLevelCode] ?? '';
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Semantics(header: true, child: Text(AppraisalText.criteriaTitle, style: t.titleMedium)),
            if (c.criteria.isEmpty) const Text(AppraisalText.noCriteria),
            for (final item in c.profile!.criteria)
              CheckboxListTile(
                key: ValueKey('criterion-${item.code}'),
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: Text(item.label),
                value: c.criteria[item.code] ?? false,
                onChanged: c.editable ? (v) => c.setCriterion(item.code, v ?? false) : null,
              ),
            if (c.criteria.isNotEmpty)
              Semantics(
                liveRegion: true,
                child: Text(AppraisalText.criteriaSummary(c.metCount, c.criteria.length, level), style: t.titleSmall),
              ),
            Text(AppraisalText.criteriaRule, style: t.bodySmall),
          ],
        ),
      ),
    );
  }

  Widget _rating(BuildContext context) {
    final t = Theme.of(context).textTheme;
    final ref = c.referenceRating;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Semantics(header: true, child: Text(AppraisalText.ratingTitle, style: t.titleMedium)),
            if (ref != null) Text(AppraisalText.reference(ratingLabel[ref] ?? '')),
            if (c.differsFromReference)
              Semantics(
                liveRegion: true,
                child: Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    AppraisalText.differsWarning,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              ),
            const SizedBox(height: 12),
            VtDynamicForm(controller: c.form!, readOnly: !c.editable),
            if (c.selfRating == 'HTXSNV') Text(AppraisalText.evidenceCount(c.profile!.evidenceIds.length)),
          ],
        ),
      ),
    );
  }

  Widget _actions() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      if (c.profile!.canSubmit)
        FilledButton(
          onPressed: c.busy || !c.editable ? null : _submit,
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          child: const Text(AppraisalText.submit),
        ),
      const SizedBox(height: 8),
      if (c.profile!.canSave)
        OutlinedButton(
          onPressed: c.editable ? () => c.saveDraft() : null,
          style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
          child: const Text(AppraisalText.saveDraft),
        ),
    ],
  );
}

class _BannerView extends StatelessWidget {
  const _BannerView({required this.banner, required this.onReload});
  final SelfAssessmentBanner banner;
  final VoidCallback onReload;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final (bg, fg) = switch (banner.kind) {
      BannerKind.error => (cs.errorContainer, cs.onErrorContainer),
      BannerKind.warning => (cs.tertiaryContainer, cs.onTertiaryContainer),
      _ => (cs.secondaryContainer, cs.onSecondaryContainer),
    };
    return Semantics(
      liveRegion: true,
      child: Container(
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(8)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(banner.text, style: TextStyle(color: fg)),
            if (banner.offerReload)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(onPressed: onReload, child: const Text(AppraisalText.reload)),
              ),
          ],
        ),
      ),
    );
  }
}

class _AwaitingSignature extends StatelessWidget {
  const _AwaitingSignature();
  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: const Icon(Icons.draw_outlined),
      title: const Text(AppraisalText.awaitingSignatureTitle),
      subtitle: const Text(AppraisalText.awaitingSignature),
    ),
  );
}

class _Retry extends StatelessWidget {
  const _Retry({required this.text, required this.onRetry});
  final String text;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(text, textAlign: TextAlign.center),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: onRetry, child: const Text(AppraisalText.retry)),
        ],
      ),
    ),
  );
}
