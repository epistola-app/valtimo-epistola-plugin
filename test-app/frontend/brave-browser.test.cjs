// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

const assert = require('node:assert/strict');
const test = require('node:test');
const { configureBraveChromeBin, findBraveBinary } = require('./brave-browser.cjs');

function executableAt(expected) {
  return (candidate) => candidate === expected;
}

test('prefers an executable BRAVE_BIN override', () => {
  const expected = '/custom/brave';
  assert.equal(
    findBraveBinary({
      env: { BRAVE_BIN: expected, PATH: '/somewhere' },
      platform: 'linux',
      home: '/home/test',
      isExecutable: executableAt(expected),
    }),
    expected,
  );
});

test('discovers Linux package, snap, and Flatpak locations', () => {
  for (const expected of [
    '/usr/bin/brave-browser',
    '/opt/brave.com/brave/brave-browser',
    '/snap/bin/brave',
    '/home/test/.local/share/flatpak/exports/bin/com.brave.Browser',
  ]) {
    assert.equal(
      findBraveBinary({
        env: {},
        platform: 'linux',
        home: '/home/test',
        isExecutable: executableAt(expected),
      }),
      expected,
    );
  }
});

test('discovers macOS system and user application bundles', () => {
  const expected =
    '/Users/test/Applications/Brave Browser Beta.app/Contents/MacOS/Brave Browser Beta';
  assert.equal(
    findBraveBinary({
      env: {},
      platform: 'darwin',
      home: '/Users/test',
      isExecutable: executableAt(expected),
    }),
    expected,
  );
});

test('discovers Windows stable, beta, and nightly installations', () => {
  const expected =
    'C:\\Program Files\\BraveSoftware\\Brave-Browser-Nightly\\Application\\brave.exe';
  assert.equal(
    findBraveBinary({
      env: { PROGRAMFILES: 'C:\\Program Files' },
      platform: 'win32',
      home: 'C:\\Users\\test',
      isExecutable: executableAt(expected),
    }),
    expected,
  );
});

test('preserves an explicit CHROME_BIN and ignores non-executable files', () => {
  const env = { CHROME_BIN: '/custom/chrome', BRAVE_BIN: '/non-executable/brave' };
  assert.equal(configureBraveChromeBin(env, { isExecutable: () => false }), '/custom/chrome');
  assert.equal(env.CHROME_BIN, '/custom/chrome');

  const withoutChrome = { BRAVE_BIN: '/non-executable/brave' };
  assert.equal(configureBraveChromeBin(withoutChrome, { isExecutable: () => false }), undefined);
  assert.equal(withoutChrome.CHROME_BIN, undefined);
});
