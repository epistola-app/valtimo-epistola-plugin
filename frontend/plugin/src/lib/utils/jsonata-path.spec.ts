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

import { renderJsonataPath, renderJsonataPathTail } from './jsonata-path';

describe('jsonata-path', () => {
  it('keeps dotted paths as path traversal', () => {
    expect(renderJsonataPath('form', 'adres.straat')).toBe('$form.adres.straat');
  });

  it('quotes only unsafe path segments', () => {
    expect(renderJsonataPath('form', 'doc:adres.straat')).toBe('$form.`doc:adres`.straat');
  });

  it('keeps single unsafe keys as one quoted segment', () => {
    expect(renderJsonataPath('form', 'pv:motivation')).toBe('$form.`pv:motivation`');
  });

  it('renders a tail for completion insert text', () => {
    expect(renderJsonataPathTail('doc:adres.straat')).toBe('`doc:adres`.straat');
  });
});
