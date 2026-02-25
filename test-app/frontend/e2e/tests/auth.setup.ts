import { test as setup, expect } from '@playwright/test';

const AUTH_FILE = 'e2e/.auth/user.json';

setup('authenticate via Keycloak', async ({ page }) => {
  // Navigate to app — Keycloak will redirect to login page
  await page.goto('/');

  // Wait for the Keycloak login form to appear
  await page.waitForURL(/.*\/realms\/.*\/protocol\/openid-connect\/auth.*/);

  // Fill credentials
  await page.getByLabel('Username or email').fill('admin');
  await page.getByLabel('Password', { exact: true }).fill('admin');

  // Submit the form
  await page.getByRole('button', { name: 'Sign In' }).click();

  // Wait for redirect back to the app
  await page.waitForURL('http://localhost:4200/**');

  // Verify the app loaded by checking for a known Valtimo element
  await expect(page.locator('valtimo-left-sidebar, .left-sidebar, nav')).toBeVisible({ timeout: 15_000 });

  // Save auth state
  await page.context().storageState({ path: AUTH_FILE });
});
