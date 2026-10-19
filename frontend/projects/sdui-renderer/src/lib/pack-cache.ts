import { UiPack } from './ui-pack';

const DB = 'vt-ui-pack';
const STORE = 'packs';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** Bộ đệm UI Pack trong IndexedDB: shell hiển thị ngay bằng bản lưu, không chờ mạng. */
export const PackCache = {
  async get(tenant: string): Promise<UiPack | undefined> {
    try {
      const db = await open();
      return await new Promise((resolve) => {
        const req = db.transaction(STORE).objectStore(STORE).get(tenant);
        req.onsuccess = () => resolve(req.result as UiPack | undefined);
        req.onerror = () => resolve(undefined);
      });
    } catch {
      return undefined;
    }
  },
  async put(tenant: string, pack: UiPack): Promise<void> {
    try {
      const db = await open();
      db.transaction(STORE, 'readwrite').objectStore(STORE).put(pack, tenant);
    } catch {
      /* chế độ riêng tư hoặc bị chặn lưu trữ: bỏ qua */
    }
  },
};
