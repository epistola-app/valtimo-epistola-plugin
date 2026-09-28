/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

jest.mock('@angular/core', () => ({
  ChangeDetectionStrategy: { OnPush: 'OnPush' },
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    emit = jest.fn();
  },
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/components', () => ({}));
jest.mock('@valtimo/plugin', () => ({
  PluginTranslatePipeModule: class {},
  PluginTranslationService: class {},
}));

import { EpistolaMappingFieldComponent } from './mapping-field.component';

describe('EpistolaMappingFieldComponent', () => {
  function createComponent() {
    return new EpistolaMappingFieldComponent(
      { markForCheck: jest.fn() } as any,
      { instant: jest.fn((key: string) => key) } as any,
    );
  }

  it('reports what a valid mapping reads', () => {
    const component = createComponent();

    component.onExpressionChange('{ "naam": $doc.aanvrager.naam, "reden": $pv.motivatie }');

    expect(component.error).toBeNull();
    expect(component.referencedPaths).toEqual(['$doc.aanvrager.naam', '$pv.motivatie']);
  });

  it('says what is wrong with an unparseable mapping', () => {
    const component = createComponent();

    component.onExpressionChange('{ "naam": $doc.(');

    expect(component.error).toBeTruthy();
    expect(component.referencedPaths).toEqual([]);
  });

  it('keeps what the author typed even while it does not parse', () => {
    // Every intermediate state of writing an expression is invalid; discarding it would be worse
    // than showing an error.
    const component = createComponent();

    component.onExpressionChange('{ "naam": $doc.(');

    expect(component.value).toBe('{ "naam": $doc.(');
    expect(component.valueChange.emit).toHaveBeenCalledWith('{ "naam": $doc.(');
  });

  it('says so when a mapping reads no case data at all', () => {
    const component = createComponent();

    component.onExpressionChange('{ "aanhef": "Geachte heer/mevrouw" }');

    expect(component.error).toBeNull();
    expect(component.referencedPaths).toEqual([]);
  });

  it('reports nothing for an empty mapping', () => {
    const component = createComponent();

    component.onExpressionChange('   ');

    expect(component.error).toBeNull();
    expect(component.referencedPaths).toEqual([]);
  });
});
