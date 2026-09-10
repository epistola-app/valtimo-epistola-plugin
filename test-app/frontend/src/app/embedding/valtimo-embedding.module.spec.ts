// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { TestBed } from '@angular/core/testing';
import { RouterModule } from '@angular/router';
import { EMBEDDING_WINDOW } from './embed-bridge.service';
import { ValtimoEmbeddingModule } from './valtimo-embedding.module';

/**
 * Wiring test, as opposed to the behavioural specs beside it. The bridge is
 * started from an environment initializer and injects the `Router` there — if
 * that were too early in the injector's life, the app would fail to bootstrap
 * rather than fail a unit test, so it is worth pinning down against the real
 * `RouterModule`.
 */
describe('ValtimoEmbeddingModule', () => {
  function fakeFramedWindow(env: Record<string, unknown>) {
    const parent = { postMessage: jasmine.createSpy('postMessage') };
    return {
      env,
      parent,
      document: { referrer: '' },
      addEventListener: () => {},
      removeEventListener: () => {},
    };
  }

  function bootstrapWith(env: Record<string, unknown>) {
    const runtimeWindow = fakeFramedWindow(env);
    TestBed.configureTestingModule({
      imports: [RouterModule.forRoot([]), ValtimoEmbeddingModule.forRoot()],
      providers: [{ provide: EMBEDDING_WINDOW, useValue: runtimeWindow }],
    });
    // Forces creation of the environment injector, which runs the initializer.
    TestBed.inject(RouterModule);
    return runtimeWindow;
  }

  it('starts the bridge on bootstrap without the Router being injected too early', () => {
    const runtimeWindow = bootstrapWith({
      embeddingEnabled: 'true',
      embeddingAllowedParentOrigins: 'https://epistola.app',
    });

    expect(runtimeWindow.parent.postMessage).toHaveBeenCalledWith(
      jasmine.objectContaining({ source: 'epistola-valtimo', type: 'ready' }),
      'https://epistola.app',
    );
  });

  it('bootstraps to a completely inert bridge when embedding is off', () => {
    // Importing the module is not the same as enabling the feature: this is
    // what lets one built image be embeddable in one environment and not another.
    const runtimeWindow = bootstrapWith({});

    expect(runtimeWindow.parent.postMessage).not.toHaveBeenCalled();
  });
});
