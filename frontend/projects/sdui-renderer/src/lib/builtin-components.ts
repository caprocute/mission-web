import { RegistryEntry } from './component-registry';

/** Component bố cục có sẵn trong shell; widget nghiệp vụ do từng remote đăng ký thêm. */
export const BUILTIN_SDUI_COMPONENTS: RegistryEntry[] = [
  {
    name: 'vt.layout.grid',
    container: true,
    since: '2.0.0',
    load: () => import('./layout/grid.component').then((m) => m.GridComponent),
    propsSchema: {
      type: 'object',
      additionalProperties: false,
      properties: { columns: { type: 'object' } },
    },
  },
];
