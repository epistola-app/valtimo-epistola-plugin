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

import { existsSync, readFileSync } from 'fs';
import { dirname, join } from 'path';
import { CARBON_MODAL, PANEL_Z_INDEX } from './floating-panel';

/**
 * What a floating preview relies on in Carbon's modal, checked against the installed version.
 *
 * A preview popped out inside a Valtimo task dialog joins the dialog's own layer and stacks around
 * its box (see `FloatingPanel`). That is Carbon's markup and styling, not ours, and nothing in the
 * compiler looks at a class name in a string — so a Valtimo upgrade that brings a Carbon with a
 * different modal would change how previews stack without failing anything. These tests read the
 * Carbon the plugin is built against (`carbon-components-angular`, pinned with Valtimo, and the
 * `@carbon/styles` it depends on) and fail the build instead. The browser suite checks the same
 * thing in a running dialog, but only nightly.
 *
 * When one fails after an upgrade: look at what Carbon changed, adapt `CARBON_MODAL` and
 * `FloatingPanel`, and run `letter-composer-preview-panel.spec.ts` against the new Valtimo.
 */
describe('Carbon modal contract', () => {
  const carbonAngular = dirname(require.resolve('carbon-components-angular/package.json'));
  const carbonStyles = dirname(
    require.resolve('@carbon/styles/package.json', { paths: [carbonAngular] }),
  );

  const read = (...candidates: string[]): string => {
    const found = candidates.find((path) => existsSync(path));
    if (!found) {
      throw new Error(`None of these exist any more: ${candidates.join(', ')}`);
    }
    return readFileSync(found, 'utf8');
  };

  const modalModule = read(
    join(carbonAngular, 'fesm2022', 'carbon-components-angular-modal.mjs'),
    join(carbonAngular, 'fesm2015', 'carbon-components-angular-modal.mjs'),
  );
  const modalStyles = read(join(carbonStyles, 'scss', 'components', 'modal', '_modal.scss'));
  const zIndexes = read(join(carbonStyles, 'scss', 'utilities', '_z-index.scss'));

  /** A component's inline template, by its selector. */
  const template = (selector: string): string => {
    const start = modalModule.indexOf(`selector: "${selector}`);
    expect(start).toBeGreaterThan(-1);
    const open = modalModule.indexOf('template: `', start);
    return modalModule.slice(open, modalModule.indexOf('`', open + 'template: `'.length));
  };

  /** One rule's declarations from `_modal.scss`, by its selector as written there. */
  const rule = (selector: string): string => {
    const start = modalStyles.indexOf(`${selector} {`);
    expect(start).toBeGreaterThan(-1);
    return modalStyles.slice(start, modalStyles.indexOf('\n  }', start));
  };

  it('opens a dialog as a layer section with the box inside it', () => {
    expect(template('cds-overlay')).toMatch(
      new RegExp(`<section[^>]*class="${CARBON_MODAL.layerClass}[ "]`),
    );
    const modal = template('cds-modal');
    expect(modal).toContain('<cds-overlay');
    expect(modal).toContain(`class="${CARBON_MODAL.dialogClass}"`);
  });

  it('puts the box at the z-index a panel stacks around', () => {
    expect(template('cds-modal')).toContain(`style="z-index:${CARBON_MODAL.dialogZIndex};"`);
    expect(PANEL_Z_INDEX.behindDialog).toBeLessThan(CARBON_MODAL.dialogZIndex);
    expect(PANEL_Z_INDEX.behindDialog).toBeGreaterThanOrEqual(0);
    expect(PANEL_Z_INDEX.inFrontOfDialog).toBeGreaterThan(CARBON_MODAL.dialogZIndex);
  });

  it('makes the layer a fixed, untransformed element whose own background is the backdrop', () => {
    // A fixed panel inside it is fixed to the window only while the layer is not transformed, and
    // "behind the box" is above the backdrop only while the backdrop is the layer's background.
    const layer = rule(`.#{$prefix}--modal`);
    expect(layer).toContain('position: fixed');
    expect(layer).toContain("z-index: z('modal')");
    expect(layer).toContain('background-color: $overlay');
    expect(layer).not.toContain('transform');
  });

  it('puts the layer at the z-index a panel outside a dialog rises above', () => {
    expect(zIndexes).toMatch(new RegExp(`\\bmodal:\\s*${CARBON_MODAL.layerZIndex}\\b`));
    expect(PANEL_Z_INDEX.onPage).toBeGreaterThan(CARBON_MODAL.layerZIndex);
  });

  it('closes a dialog only on a click on the layer itself, never on a panel inside it', () => {
    const handler = modalModule.slice(modalModule.indexOf('overlayClick(event) {'));
    expect(handler).toMatch(
      /^overlayClick\(event\) \{\s*if \(event\.target !== this\.overlay\.nativeElement\) \{\s*return;/,
    );
  });
});
