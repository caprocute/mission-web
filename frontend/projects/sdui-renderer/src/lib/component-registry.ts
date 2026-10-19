import { Injectable, InjectionToken, Type, inject, makeEnvironmentProviders } from '@angular/core';

/** Lược đồ thuộc tính được phép (tập con JSON Schema đủ cho props của widget). */
export interface PropsSchema {
  type: 'object';
  additionalProperties?: false;
  properties: Record<string, { type: 'string' | 'integer' | 'number' | 'boolean' | 'object' | 'array'; minimum?: number; maximum?: number; enum?: unknown[]; pattern?: string }>;
}

export interface RegistryEntry {
  /** Tên trong UI Pack, ví dụ `vt.widget.myTasksToday`. */
  name: string;
  /** Nạp lười, tách bundle theo component (có thể nạp từ remote Native Federation). */
  load: () => Promise<Type<unknown>>;
  propsSchema: PropsSchema;
  /** Được chứa nút con hay không. */
  container?: boolean;
  /** Phiên bản registry đưa component vào. */
  since: string;
}

export const SDUI_COMPONENTS = new InjectionToken<RegistryEntry[][]>('vt.sduiComponents');

/** Phiên bản registry phát hành cùng shell; UI Pack khai báo dải tương thích `requires.registry`. */
export const REGISTRY_VERSION = '2.4.0';

/** Đăng ký thêm component cho SDUI; shell và remote cùng dùng hàm này. */
export function provideSduiComponents(entries: RegistryEntry[]) {
  return makeEnvironmentProviders([{ provide: SDUI_COMPONENTS, useValue: entries, multi: true }]);
}

@Injectable({ providedIn: 'root' })
export class ComponentRegistry {
  private readonly entries = new Map<string, RegistryEntry>();

  constructor() {
    const groups = inject(SDUI_COMPONENTS, { optional: true }) ?? [];
    for (const group of groups) this.register(group);
  }

  register(group: RegistryEntry[]): void {
    for (const e of group) this.entries.set(e.name, e);
  }

  get(name: string): RegistryEntry | undefined {
    return this.entries.get(name);
  }

  names(): string[] {
    return [...this.entries.keys()].sort();
  }
}
