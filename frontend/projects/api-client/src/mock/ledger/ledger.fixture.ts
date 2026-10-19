import { LedgerEntry, LedgerVerifyRun } from '../../lib/models/ledger';

/**
 * Dữ liệu mẫu sổ cái SVC-09 (WEB-043): chuỗi bản ghi của hồ sơ Trần Minh Hoàng Quý II/2026 và các đối
 * tượng liên quan, theo TL-02 Hình 11.8 nhưng đặt trong 90 ngày gần nhất để bộ lọc mặc định thấy được.
 * Phân vùng 17 có bản ghi 1042 bị sửa sau khi ghi: băm lưu `4b07…` khác băm tính lại `9d3c…`.
 */

/** Băm giả xác định (FNV-1a lặp 8 lần) cho 64 ký tự hex; chỉ để hiển thị, không phải SHA-256. */
export function fakeHash(seed: string): string {
  let out = '';
  for (let k = 0; k < 8; k++) {
    let h = 0x811c9dc5 ^ k;
    for (let i = 0; i < seed.length; i++) {
      h ^= seed.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    out += h.toString(16).padStart(8, '0');
  }
  return out;
}

export const TAMPERED_SEQ = 1042;
export const TAMPERED_PARTITION = 17;
export const TAMPERED_STORED = '4b07c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8';
export const TAMPERED_RECOMPUTED =
  '9d3c0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7';

export const PROFILE_AGG = 'ap-2026q2-tran-minh-hoang';

/** Bản ghi kèm các băm nội bộ để dựng chi tiết và kiểm tra chuỗi. */
export interface StoredEntry extends LedgerEntry {
  prevHash: string;
  payloadHash: string;
  metadataHash: string;
  /** Băm tính lại từ nội dung hiện tại; khác `entryHash` khi bản ghi bị sửa. */
  recomputedHash: string;
}

const PEOPLE = {
  hoang: { id: 'u-tran-minh-hoang', name: 'Trần Minh Hoàng' },
  tam: { id: 'u-ngo-thanh-tam', name: 'Ngô Thanh Tâm' },
  ha: { id: 'u-le-thu-ha', name: 'Lê Thu Hà' },
  lan: { id: 'u-vu-thi-lan', name: 'Vũ Thị Lan' },
  quan: { id: 'u-phan-van-quan', name: 'Phan Văn Quân' },
};

interface Seed {
  daysAgo: number;
  time: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  actor?: { id: string; name: string };
  actorKind: LedgerEntry['actorKind'];
  metadata: Record<string, unknown>;
}

/** Diễn biến của hồ sơ mẫu, cũ trước; bản ghi thứ 13 của phân vùng 17 là 1042 (bị sửa). */
const STORY: Seed[] = [
  {
    daysAgo: 86,
    time: '08:12',
    eventType: 'task.task.assigned.v1',
    aggregateType: 'task',
    aggregateId: 'task-tc-3',
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: { after: { status: 'DA_GIAO', task: 'Then chốt 3' } },
  },
  {
    daysAgo: 84,
    time: '09:30',
    eventType: 'task.task.accepted.v1',
    aggregateType: 'task',
    aggregateId: 'task-tc-3',
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { before: { status: 'DA_GIAO' }, after: { status: 'DANG_THUC_HIEN' } },
  },
  {
    daysAgo: 80,
    time: '14:05',
    eventType: 'task.task.progress.v1',
    aggregateType: 'task',
    aggregateId: 'task-tc-3',
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { before: { progress: 40 }, after: { progress: 70 } },
  },
  {
    daysAgo: 76,
    time: '10:44',
    eventType: 'product.evidence.submitted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { after: { evidence: 'Báo cáo tổng hợp quý', version: 1 } },
  },
  {
    daysAgo: 74,
    time: '15:20',
    eventType: 'product.evidence.accepted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: { before: { c: null }, after: { c: 0.92, b: 1 } },
  },
  {
    daysAgo: 70,
    time: '09:02',
    eventType: 'product.evidence.submitted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { after: { evidence: 'Kế hoạch kiểm tra chuyên đề', version: 2 } },
  },
  {
    daysAgo: 66,
    time: '16:48',
    eventType: 'product.evidence.accepted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: { before: { c: null }, after: { c: 0.85, b: 0.75 } },
  },
  {
    daysAgo: 60,
    time: '08:00',
    eventType: 'config.period.locked.v1',
    aggregateType: 'period',
    aggregateId: 'p-2026q2',
    actor: PEOPLE.lan,
    actorKind: 'NGUOI',
    metadata: { before: { status: 'DANG_KHOA' }, after: { status: 'DA_KHOA' } },
  },
  {
    daysAgo: 58,
    time: '10:15',
    eventType: 'appraisal.self_assessment.submitted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { after: { selfLevel: 'HTXSNV' } },
  },
  {
    daysAgo: 55,
    time: '11:20',
    eventType: 'appraisal.score.computed.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actorKind: 'DICH_VU',
    metadata: { after: { score: 91.2, level: 'HTTNV', policyVersion: 'khối Đảng, phiên bản 2.0' } },
  },
  {
    daysAgo: 52,
    time: '14:33',
    eventType: 'appraisal.appraisal.completed.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: { before: { level: null }, after: { level: 'HTTNV' } },
  },
  {
    daysAgo: 48,
    time: '09:40',
    eventType: 'appraisal.decision.signed.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.quan,
    actorKind: 'NGUOI',
    metadata: { after: { level: 'HTTNV', signature: 'Đã ký' } },
  },
  {
    daysAgo: 40,
    time: '15:00',
    eventType: 'product.evidence.accepted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: {
      before: { c: 0.85 },
      after: { c: 0.95 },
      note: 'Nội dung bị sửa trực tiếp trong CSDL',
    },
  },
  {
    daysAgo: 32,
    time: '10:05',
    eventType: 'oversight.complaint.submitted.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.hoang,
    actorKind: 'NGUOI',
    metadata: { after: { complaint: 'KN-2026-0031', content: '[Nội dung được mã hoá khi lưu]' } },
  },
  {
    daysAgo: 12,
    time: '16:20',
    eventType: 'oversight.complaint.concluded.v1',
    aggregateType: 'appraisal_profile',
    aggregateId: PROFILE_AGG,
    actor: PEOPLE.ha,
    actorKind: 'NGUOI',
    metadata: { after: { conclusion: 'CHAP_NHAN_MOT_PHAN' } },
  },
  {
    daysAgo: 2,
    time: '15:40',
    eventType: 'oversight.reopen_request.created.v1',
    aggregateType: 'reopen_request',
    aggregateId: 'rr-2026-007',
    actor: PEOPLE.ha,
    actorKind: 'NGUOI',
    metadata: { after: { code: 'MLK-2026-007', status: 'CHO_DUYET_1' } },
  },
  {
    daysAgo: 1,
    time: '10:14',
    eventType: 'oversight.reopen_request.approved.v1',
    aggregateType: 'reopen_request',
    aggregateId: 'rr-2026-007',
    actor: PEOPLE.tam,
    actorKind: 'NGUOI',
    metadata: { before: { status: 'CHO_DUYET_1' }, after: { status: 'CHO_DUYET_2', round: 1 } },
  },
  {
    daysAgo: 0,
    time: '00:05',
    eventType: 'ledger.seal.created.v1',
    aggregateType: 'period',
    aggregateId: 'p-2026q2',
    actorKind: 'HE_THONG',
    metadata: { after: { partitions: 64 } },
  },
];

/**
 * Tên hiển thị của đối tượng (`metadata.aggregateLabel`, suy luận): máy chủ gửi kèm để màn hình không phải
 * hiện `aggregate_id` nội bộ.
 */
const AGGREGATE_LABEL: Record<string, string> = {
  'task-tc-3': 'NV-2026-Q2-0031 · Nhiệm vụ then chốt 3',
  [PROFILE_AGG]: 'Trần Minh Hoàng, Quý II/2026',
  'p-2026q2': 'Quý II/2026',
  'rr-2026-007': 'MLK-2026-007',
};

const at = (daysAgo: number, time: string) => {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  const [h, m] = time.split(':').map(Number);
  d.setHours(h, m, 7, 0);
  return d.toISOString();
};

/** Chuỗi phân vùng 17: bản ghi 1030 trở đi, `prevHash` của bản ghi sau là `entryHash` lưu của bản ghi trước. */
export function ledgerFixture(): StoredEntry[] {
  const out: StoredEntry[] = [];
  let prev = fakeHash('genesis-17');
  STORY.forEach((s, i) => {
    const seqNo = 1030 + i;
    const id = `le-17-${seqNo}`;
    const occurredAt = at(s.daysAgo, s.time);
    const metadata = { ...s.metadata, aggregateLabel: AGGREGATE_LABEL[s.aggregateId] };
    const content = JSON.stringify([prev, id, s.eventType, s.aggregateId, metadata]);
    const recomputed = fakeHash(content);
    const tampered = seqNo === TAMPERED_SEQ;
    const entryHash = tampered ? TAMPERED_STORED : recomputed;
    out.push({
      id,
      chainPartition: TAMPERED_PARTITION,
      seqNo,
      eventId: `ev-${seqNo}`,
      eventType: s.eventType,
      aggregateType: s.aggregateType,
      aggregateId: s.aggregateId,
      ...(s.actor ? { actor: s.actor } : {}),
      actorKind: s.actorKind,
      occurredAt,
      recordedAt: new Date(Date.parse(occurredAt) + 1000).toISOString(),
      metadata,
      entryHash,
      prevHash: prev,
      payloadHash: fakeHash(`payload-${id}`),
      metadataHash: fakeHash(`meta-${id}`),
      recomputedHash: tampered ? TAMPERED_RECOMPUTED : recomputed,
    });
    prev = entryHash;
  });
  return out;
}

/** Hai lần kiểm tra: hằng đêm hôm trước (khớp hết) và theo yêu cầu sáng nay (phá vỡ tại 1042). */
export function verifyRunFixtures(entries: StoredEntry[]): LedgerVerifyRun[] {
  const broken = entries.find((e) => e.seqNo === TAMPERED_SEQ)!;
  const today = (hhmm: string, offsetDays = 0) => at(offsetDays, hhmm);
  return [
    {
      id: 'vr-2026-0112',
      code: 'VR-2026-0112',
      verifyKind: 'TOAN_BO',
      chainPartition: null,
      rangeFrom: at(290, '00:00'),
      rangeTo: today('07:00'),
      entriesChecked: 1_284_311,
      sealsChecked: 7_224,
      partitionsTotal: 64,
      partitionsMatched: 63,
      mismatches: 1,
      firstMismatch: {
        chainPartition: TAMPERED_PARTITION,
        seqNo: TAMPERED_SEQ,
        entryId: broken.id,
        storedHash: TAMPERED_STORED,
        recomputedHash: TAMPERED_RECOMPUTED,
        sealId: `seal-${broken.id}`,
        anchorMatches: true,
      },
      chain: chainAround(entries, TAMPERED_SEQ),
      alertId: 'cb-2026-0140',
      startedAt: today('07:00'),
      finishedAt: new Date(Date.parse(today('07:00')) + 48_000).toISOString(),
    },
    {
      id: 'vr-2026-0111',
      code: 'VR-2026-0111',
      verifyKind: 'DAU_CHUOI',
      chainPartition: null,
      rangeFrom: at(31, '00:00'),
      rangeTo: at(1, '02:00'),
      entriesChecked: 96_420,
      sealsChecked: 744,
      partitionsTotal: 64,
      partitionsMatched: 64,
      mismatches: 0,
      firstMismatch: null,
      chain: [],
      alertId: null,
      startedAt: at(1, '02:00'),
      finishedAt: new Date(Date.parse(at(1, '02:00')) + 6_000).toISOString(),
    },
  ];
}

/** Ba mắt chuỗi quanh bản ghi `seqNo` để vẽ sơ đồ khối (SCR-1302 vùng 3). */
export function chainAround(entries: StoredEntry[], seqNo: number) {
  return entries
    .filter((e) => Math.abs(e.seqNo - seqNo) <= 1)
    .map((e) => ({
      seqNo: e.seqNo,
      entryId: e.id,
      prevHash: e.prevHash,
      storedHash: e.entryHash,
      ...(e.entryHash !== e.recomputedHash ? { recomputedHash: e.recomputedHash } : {}),
      matches: e.entryHash === e.recomputedHash,
    }));
}
