import type { VtPlugin } from '@vt/sdui-renderer';

/**
 * Mô tả plugin Giáo dục cho shell (hợp đồng `VtPlugin`, WEB-051). Tuyến gắn dưới `/plugin/giaoduc`;
 * widget mang tiền tố `giaoduc.widget.` để UI Pack của tenant đặt lên trang chủ.
 */
export const VT_PLUGIN: VtPlugin = {
  name: 'fe-plugin-giaoduc',
  title: 'Chuyên môn giáo dục',
  subtitle: 'Nhiệm vụ chuyên môn của nhà trường',
  icon: 'pi pi-book',
  routes: () => import('./app/plugin-giaoduc.routes').then((m) => m.ROUTES),
  widgets: [
    {
      name: 'giaoduc.widget.tienDoChuyenMon',
      since: '2.4.0',
      propsSchema: { type: 'object', additionalProperties: false, properties: {} },
      load: () => import('./app/tien-do-chuyen-mon.widget').then((m) => m.TienDoChuyenMonWidget),
    },
  ],
};
