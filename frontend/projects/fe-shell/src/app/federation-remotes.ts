import { loadRemoteModule } from '@angular-architects/native-federation';

/** Khoá remote (ví dụ `feTask`) → URL `remoteEntry.json`, đọc từ `federation.manifest.json`. */
export type RemoteManifest = Record<string, string>;

let manifest: Promise<RemoteManifest> = Promise.resolve({});
/** Lượt nạp đầu tiên của mỗi remote; lượt sau chờ lượt này để không đăng ký remote hai lần. */
const firstLoad = new Map<string, Promise<unknown>>();

/** main.ts gọi một lần với manifest đang tải. */
export function setRemoteManifest(m: Promise<RemoteManifest>): void {
  manifest = m;
}

/**
 * Nạp một module mà remote công bố, đăng ký remote khi cần lần đầu (WEB-052).
 * Trước WEB-052, `initFederation` tải `remoteEntry.json` của cả chín remote trước khi shell khởi
 * động, dù trang chủ chỉ cần fe-task; nay mỗi remote được đăng ký (tải `remoteEntry.json`, bổ sung
 * import map) ở lần đầu một tuyến hoặc widget của nó được nạp. Danh mục remote vẫn do
 * `federation.manifest.json` (CDN, tenant) quyết định như WEB-015.
 */
export async function loadRemote<T = Record<string, unknown>>(
  remoteName: string,
  exposedModule: string,
): Promise<T> {
  const remoteEntry = (await manifest)[remoteName];
  if (!remoteEntry) {
    throw new Error(`Không có remote "${remoteName}" trong federation.manifest.json`);
  }
  const first = firstLoad.get(remoteName);
  if (first) {
    await first.catch(() => undefined);
    return loadRemoteModule<T>({ remoteEntry, exposedModule });
  }
  const load = loadRemoteModule<T>({ remoteEntry, exposedModule });
  firstLoad.set(remoteName, load);
  // lỗi (remote chưa triển khai, mạng) thì lần sau thử đăng ký lại
  load.catch(() => firstLoad.delete(remoteName));
  return load;
}
