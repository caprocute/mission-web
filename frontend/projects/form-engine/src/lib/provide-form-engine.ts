import { Component, makeEnvironmentProviders } from '@angular/core';
import { FieldWrapper, provideFormlyCore } from '@ngx-formly/core';
import { withFormlyPrimeNG } from '@ngx-formly/primeng';

@Component({
  selector: 'vt-formly-panel',
  template: `
    <fieldset class="vt-panel">
      <legend>{{ props.label }}</legend>
      <ng-container #fieldComponent />
    </fieldset>
  `,
  styles: `.vt-panel { border: 1px solid var(--p-content-border-color, #e5e7eb); border-radius: var(--vt-radius-card, 8px); padding: 12px 16px; margin-bottom: 16px; } legend { font-weight: 600; padding: 0 6px; }`,
})
export class PanelWrapperComponent extends FieldWrapper {}

export function provideFormEngine() {
  return makeEnvironmentProviders([
    provideFormlyCore([
      ...withFormlyPrimeNG(),
      { wrappers: [{ name: 'panel', component: PanelWrapperComponent }] },
    ]),
  ]);
}
