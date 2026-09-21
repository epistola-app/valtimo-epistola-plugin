// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import {
  ModuleWithProviders,
  NgModule,
  provideEnvironmentInitializer,
  inject,
} from '@angular/core';
import { EmbedBridgeService } from './embed-bridge.service';

/**
 * Wires up the iframe embedding bridge. Import once, in the root module.
 *
 * Importing it is not the same as turning embedding on: the bridge reads
 * `window['env']` at runtime and stays completely inert — no listener, no
 * `postMessage` — unless embedding is explicitly enabled, at least one valid
 * parent origin is configured, and the app is actually framed. That is what
 * lets a single built image be embeddable in one environment and not another.
 *
 * The initializer runs before the Router's first navigation, so the very first
 * `NavigationEnd` is reported like any other.
 */
@NgModule({
  providers: [
    EmbedBridgeService,
    provideEnvironmentInitializer(() => inject(EmbedBridgeService).start()),
  ],
})
export class ValtimoEmbeddingModule {
  /** Kept for symmetry with the other Valtimo modules' setup style; providers are module-level. */
  static forRoot(): ModuleWithProviders<ValtimoEmbeddingModule> {
    return { ngModule: ValtimoEmbeddingModule };
  }
}
