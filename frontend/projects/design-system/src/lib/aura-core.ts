import base from '@primeuix/themes/aura/base';
import * as auraCss from '@primeuix/themes/aura/css';
import avatar from '@primeuix/themes/aura/avatar';
import badge from '@primeuix/themes/aura/badge';
import button from '@primeuix/themes/aura/button';
import card from '@primeuix/themes/aura/card';
import checkbox from '@primeuix/themes/aura/checkbox';
import chip from '@primeuix/themes/aura/chip';
import dialog from '@primeuix/themes/aura/dialog';
import drawer from '@primeuix/themes/aura/drawer';
import iconfield from '@primeuix/themes/aura/iconfield';
import inputnumber from '@primeuix/themes/aura/inputnumber';
import inputtext from '@primeuix/themes/aura/inputtext';
import menu from '@primeuix/themes/aura/menu';
import message from '@primeuix/themes/aura/message';
import multiselect from '@primeuix/themes/aura/multiselect';
import ripple from '@primeuix/themes/aura/ripple';
import select from '@primeuix/themes/aura/select';
import selectbutton from '@primeuix/themes/aura/selectbutton';
import slider from '@primeuix/themes/aura/slider';
import tabs from '@primeuix/themes/aura/tabs';
import tag from '@primeuix/themes/aura/tag';
import textarea from '@primeuix/themes/aura/textarea';
import toast from '@primeuix/themes/aura/toast';
import togglebutton from '@primeuix/themes/aura/togglebutton';
import tooltip from '@primeuix/themes/aura/tooltip';
import type { ExtendedCSS, Preset } from '@primeuix/themes/types';

/**
 * WEB-052 pha 2: token Aura của đúng các component PrimeNG mà shell và các remote dùng (kể cả
 * component con mà chúng dựng bên trong, ví dụ `p-selectbutton` dựng `p-togglebutton`), thay cho
 * gói `@primeuix/themes/aura` đầy đủ hơn 80 component. Giá trị token giữ nguyên của Aura, chỉ bỏ
 * component không dùng, nên giảm khoảng 5,5 KB gzip trong gói design system của lần tải đầu.
 *
 * Dùng thêm component PrimeNG mới thì phải thêm token của nó vào đây, nếu không component hiện
 * không có kiểu; `aura-core.spec.ts` quét mã nguồn và báo thiếu.
 */
export const AURA_COMPONENTS = {
  avatar,
  badge,
  button,
  card,
  checkbox,
  chip,
  dialog,
  drawer,
  iconfield,
  inputnumber,
  inputtext,
  menu,
  message,
  multiselect,
  ripple,
  select,
  selectbutton,
  slider,
  tabs,
  tag,
  textarea,
  // WEB-012: <p-toast> của shell và phần nhúng hiện thông báo lỗi chung của ProblemPresenter
  toast,
  togglebutton,
  tooltip,
};

/** CSS bổ sung của Aura; tệp chỉ có default export dù kiểu khai báo tên `css`. */
const css = (auraCss as unknown as { default: ExtendedCSS }).default;

/** Preset Aura rút gọn: token nền (primitive, semantic), CSS bổ sung và token các component trên. */
export const AuraCore: Preset = { ...base, components: AURA_COMPONENTS, css } as Preset;
