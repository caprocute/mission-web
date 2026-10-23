import { expect, request } from '@playwright/test';
import { MOI_TRUONG } from './moi-truong';

/**
 * Kiểm sự kiện EVT trên Kafka dev qua API của kafka-ui (kafbat/kafka-ui hoặc provectus/kafka-ui),
 * không mở cổng Kafka từ máy chạy kiểm thử. Lọc theo khoá tổng hợp (mã bản ghi nghiệp vụ, ví dụ mã
 * nhiệm vụ là `aggregate_id` của outbox) và chỉ xét bản tin từ thời điểm `tu`.
 */
export interface BanTin {
  key: string | null;
  content: string | null;
  timestamp: string | null;
  headers: Record<string, string>;
}

/** Có cấu hình đọc topic không; không có thì hành trình ghi chú bước kiểm sự kiện là "chờ". */
export const coKafkaUi = () => MOI_TRUONG.kafkaUi !== '';

/** Đọc luồng SSE `data: {...}` của kafka-ui, lấy các sự kiện loại MESSAGE. */
function docSse(text: string): BanTin[] {
  const out: BanTin[] = [];
  const lines = text.trim().startsWith('[') ? [text] : text.split('\n');
  for (const raw of lines) {
    const line = raw.startsWith('data:') ? raw.slice(5).trim() : raw.trim();
    if (!line || (!line.startsWith('{') && !line.startsWith('['))) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(line);
    } catch {
      continue;
    }
    for (const ev of Array.isArray(parsed) ? parsed : [parsed]) {
      const e = ev as { type?: string; message?: Record<string, unknown> };
      if (e.type === 'MESSAGE' && e.message) {
        out.push({
          key: (e.message['key'] as string) ?? null,
          content: (e.message['content'] as string) ?? null,
          timestamp: (e.message['timestamp'] as string) ?? null,
          headers: (e.message['headers'] as Record<string, string>) ?? {},
        });
      }
    }
  }
  return out;
}

async function doc(topic: string, loc: string, tu: Date): Promise<BanTin[]> {
  const ctx = await request.newContext({ baseURL: `${MOI_TRUONG.kafkaUi}/` });
  try {
    const base = `api/clusters/${encodeURIComponent(MOI_TRUONG.kafkaCluster)}/topics/${encodeURIComponent(topic)}`;
    // kafbat/kafka-ui 1.x: /messages/v2; bản provectus cũ: /messages với seekType
    let r = await ctx.get(`${base}/messages/v2`, {
      params: {
        mode: 'FROM_TIMESTAMP',
        timestamp: String(tu.getTime()),
        limit: '200',
        stringFilter: loc,
      },
    });
    if (r.status() === 404) {
      r = await ctx.get(`${base}/messages`, {
        params: {
          seekType: 'TIMESTAMP',
          seekTo: `0::${tu.getTime()}`,
          limit: '200',
          q: loc,
          filterQueryType: 'STRING_CONTAINS',
        },
      });
    }
    expect(r.status(), `kafka-ui trả ${r.status()} khi đọc ${topic}`).toBeLessThan(400);
    return docSse(await r.text());
  } finally {
    await ctx.dispose();
  }
}

/**
 * Chờ (tối đa `timeout`) tới khi topic có bản tin sau `tu` mang khoá hoặc nội dung chứa `loc`.
 * Bản tin Avro hiện ở kafka-ui dạng đã giải mã hoặc nhị phân; mã UUID nằm trong chuỗi nên đều lọc được.
 */
export async function choSuKien(
  topic: string,
  loc: string,
  tu: Date,
  timeout = 60_000,
): Promise<BanTin> {
  let thay: BanTin | undefined;
  await expect
    .poll(
      async () => {
        const ds = await doc(topic, loc, tu);
        thay = ds.find((m) => (m.key ?? '').includes(loc) || (m.content ?? '').includes(loc));
        return Boolean(thay);
      },
      {
        message: `không thấy bản tin ${topic} cho ${loc}`,
        timeout,
        intervals: [2_000, 3_000, 5_000],
      },
    )
    .toBe(true);
  return thay!;
}
