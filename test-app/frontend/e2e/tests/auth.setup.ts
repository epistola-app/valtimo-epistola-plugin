import { test as setup, expect } from '@playwright/test';

const AUTH_FILE = 'e2e/.auth/user.json';

setup('authenticate via Keycloak', async ({ page }) => {
  // Navigate to app — Keycloak will redirect to login page
  await page.goto('/');

  // Wait for the Keycloak login form to appear
  await page.waitForURL(/.*\/realms\/.*\/protocol\/openid-connect\/auth.*/);

  // Fill credentials. Selectors target the stable Keycloak input ids so the
  // setup keeps working across Keycloak versions where the label text differs
  // ("Username or email" vs "Email").
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill('admin');

  // Submit the form
  await page.getByRole('button', { name: 'Sign In' }).click();

  // Wait for redirect back to the app
  await page.waitForURL('http://localhost:4200/**');

  // Verify the app loaded by checking for the side-navigation. We target the
  // ARIA role rather than the (often display:none) Angular wrapper element,
  // because the modern Valtimo Carbon-based UI renders the actual nav inside
  // a hidden host component.
  await expect(page.getByRole('navigation', { name: /Side navigation/i })).toBeVisible({
    timeout: 15_000,
  });

  // Save auth state
  await page.context().storageState({ path: AUTH_FILE });
});
