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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import { createRequire } from 'node:module';
import { realpathSync } from 'node:fs';
import { resolve } from 'node:path';

const appRequire = createRequire(resolve('test-app/frontend/package.json'));
const pluginPackage = appRequire.resolve('@epistola.app/valtimo-plugin/package.json');
const componentsPackage = appRequire.resolve('@valtimo/components/package.json');
const pluginRequire = createRequire(pluginPackage);
const componentsRequire = createRequire(componentsPackage);

const hostSingletons = [
  '@angular/core',
  '@angular/common',
  '@angular/elements',
  '@angular/forms',
  '@angular/platform-browser',
  '@angular/router',
  '@valtimo/components',
  '@valtimo/plugin',
  '@valtimo/process-link',
  '@valtimo/security',
  '@valtimo/shared',
  'carbon-components-angular',
  'rxjs',
];

const formioSingletons = ['@formio/angular', 'formiojs'];

function packagePath(requireFrom, packageName) {
  return realpathSync(requireFrom.resolve(`${packageName}/package.json`));
}

function assertSamePackage(packageName, expectedRequire, actualRequire) {
  const expected = packagePath(expectedRequire, packageName);
  const actual = packagePath(actualRequire, packageName);

  if (actual !== expected) {
    throw new Error(
      `${packageName} resolves to different instances:\n` +
        `  host:   ${expected}\n` +
        `  plugin: ${actual}`,
    );
  }
}

for (const packageName of hostSingletons) {
  assertSamePackage(packageName, appRequire, pluginRequire);
}

for (const packageName of formioSingletons) {
  assertSamePackage(packageName, componentsRequire, pluginRequire);
}

console.log('Frontend plugin and host resolve the same singleton packages.');
