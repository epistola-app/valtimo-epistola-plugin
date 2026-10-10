// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * The composer's **authoring** side, in the Form.io builder.
 *
 * Every other browser test here covers the employee: pick a letter, preview it, send it. Nothing
 * covered the author, and the widgets they use — the letter set, the write-back rules, the mapping
 * fields — had never been rendered by any test at all. The unit specs construct these components
 * directly and never compile their templates, and `pnpm build` compiles them without ever drawing
 * one, so a component could render nothing and every gate would stay green.
 *
 * This opens a form that holds a composer and drives its settings the way an author does.
 *
 * Two things learned the hard way and worth keeping:
 *
 * - **The form list does not navigate on a click.** Valtimo's Carbon table has no links in its rows
 *   and a forced click does nothing, so this opens the form directly by id — which is also why the
 *   fixture is created through the API rather than through the UI.
 * - **The settings button is hidden until hover.** Form.io reveals a component's buttons on hover,
 *   so clicking `[ref="editComponent"]` without hovering first times out on an invisible element.
 *
 * The fixture is created and deleted by the test, so the demo's own forms are never touched and
 * nothing is left behind.
 */

const KEYCLOAK_TOKEN_URL = 'http://localhost:8081/realms/valtimo/protocol/openid-connect/token';
const FORM_API = 'http://localhost:8080/api/management/v1/form';

/** The letters this fixture offers. The first is configured; the second proves the gating. */
const OFFERED = 'besluit-bezwaar';
const NOT_OFFERED = 'belastingbrief';

async function adminToken(request: APIRequestContext): Promise<string> {
  const response = await request.post(KEYCLOAK_TOKEN_URL, {
    form: {
      client_id: 'valtimo-console',
      grant_type: 'password',
      username: 'admin',
      password: 'admin',
    },
  });
  expect(response.ok()).toBe(true);
  return (await response.json()).access_token;
}

/**
 * A form holding one composer, offering one letter of the demo catalog.
 *
 * `formDefinition` goes over the wire as a **string**, and the composer's settings nest under
 * `epistola.letterSet` — the one key this component claims, and the shape both the editForm and
 * `ComposerParser` read. Both are easy to get wrong and fail silently: the widget renders either
 * way and simply offers nothing.
 */
async function createFixtureForm(
  request: APIRequestContext,
  token: string,
  pluginConfigurationId: string,
): Promise<string> {
  const definition = {
    display: 'form',
    components: [
      {
        type: 'epistola-letter-composer',
        key: 'pv:epistolaLetter',
        label: 'Brief',
        input: true,
        epistola: {
          schemaVersion: 1,
          letterSet: {
            pluginConfigurationId,
            catalogId: 'municipality-demo',
            templates: [{ templateId: OFFERED, label: 'Besluit' }],
          },
        },
      },
    ],
  };
  const response = await request.post(FORM_API, {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `e2e-letter-set-${Date.now()}`, formDefinition: JSON.stringify(definition) },
  });
  expect(response.ok()).toBe(true);
  return (await response.json()).id;
}

async function openComposerSettings(page: Page, formId: string): Promise<void> {
  await page.goto(`/form-management/${formId}`);
  await page.waitForLoadState('domcontentloaded');

  const component = page.locator('.builder-component').first();
  await component.waitFor({ timeout: 30_000 });
  await component.hover();
  await page.locator('[ref="editComponent"]').first().click();

  // The widget is an Angular element inside Form.io's dialog; it loads its connections, catalogs
  // and templates before it can offer anything.
  await expect(page.locator(`[data-testid="epistola-letter-set-offer-${OFFERED}"]`)).toBeVisible({
    timeout: 30_000,
  });
}

test.describe('Letter set builder — configuring a composer', () => {
  let formId: string;
  let token: string;

  test.beforeAll(async ({ playwright }) => {
    const request = await playwright.request.newContext();
    token = await adminToken(request);
    const configurations = await request.get(
      'http://localhost:8080/api/v1/plugin/epistola/admin/health',
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(configurations.ok()).toBe(true);
    const pluginConfigurationId = (await configurations.json())[0].configurationId;
    formId = await createFixtureForm(request, token, pluginConfigurationId);
    await request.dispose();
  });

  test.afterAll(async ({ playwright }) => {
    const request = await playwright.request.newContext();
    await request.delete(`${FORM_API}/${formId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    await request.dispose();
  });

  test('offers its letters, and configures one of them on its own', async ({ page }) => {
    test.setTimeout(180_000);
    await openComposerSettings(page, formId);

    // Configuring is offered for a letter this composer actually offers, and for no other. The
    // table lists every template in the catalog, so this is the difference between "in the catalog"
    // and "in this letter set".
    await expect(
      page.getByTestId(`epistola-letter-set-configure-${OFFERED}`),
      'a letter that is offered can be configured',
    ).toBeVisible();
    await expect(
      page.getByTestId(`epistola-letter-set-configure-${NOT_OFFERED}`),
      'a letter that is only in the catalog cannot',
    ).toHaveCount(0);

    // The panel is closed until asked for: a letter set with twenty letters should not open twenty
    // panels.
    await expect(page.getByTestId(`epistola-letter-set-settings-${OFFERED}`)).toHaveCount(0);

    await page.getByTestId(`epistola-letter-set-configure-${OFFERED}`).click();
    const panel = page.getByTestId(`epistola-letter-set-settings-${OFFERED}`);
    await expect(panel).toBeVisible();

    // The one setting that lives there so far: a mapping fragment merged over the baseline for this
    // letter alone.
    const mapping = page.getByTestId(`epistola-letter-set-mapping-${OFFERED}`);
    await mapping.fill('{ "aanhef": "Geachte heer" }');
    await expect(mapping).toHaveValue('{ "aanhef": "Geachte heer" }');

    // And it closes again, which is how an author moves to the next letter.
    await page.getByTestId(`epistola-letter-set-configure-${OFFERED}`).click();
    await expect(page.getByTestId(`epistola-letter-set-settings-${OFFERED}`)).toHaveCount(0);
  });
});
