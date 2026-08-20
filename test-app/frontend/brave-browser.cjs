// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const EXECUTABLE_NAMES = [
  'brave-browser',
  'brave-browser-stable',
  'brave-browser-beta',
  'brave-browser-nightly',
  'brave',
  'brave.exe',
];

function braveCandidates({ env, platform, home }) {
  const pathApi = platform === 'win32' ? path.win32 : path.posix;
  const delimiter = platform === 'win32' ? ';' : ':';
  const pathCandidates = (env.PATH || '')
    .split(delimiter)
    .filter(Boolean)
    .flatMap((directory) => EXECUTABLE_NAMES.map((name) => pathApi.join(directory, name)));

  let installedCandidates = [];
  if (platform === 'darwin') {
    installedCandidates = ['Brave Browser', 'Brave Browser Beta', 'Brave Browser Nightly'].flatMap(
      (application) => [
        pathApi.join('/Applications', `${application}.app/Contents/MacOS`, application),
        pathApi.join(home, 'Applications', `${application}.app/Contents/MacOS`, application),
      ],
    );
  } else if (platform === 'win32') {
    installedCandidates = [env.LOCALAPPDATA, env.PROGRAMFILES, env['PROGRAMFILES(X86)']]
      .filter(Boolean)
      .flatMap((root) => [
        pathApi.join(root, 'BraveSoftware/Brave-Browser/Application/brave.exe'),
        pathApi.join(root, 'BraveSoftware/Brave-Browser-Beta/Application/brave.exe'),
        pathApi.join(root, 'BraveSoftware/Brave-Browser-Nightly/Application/brave.exe'),
      ]);
  } else {
    installedCandidates = [
      '/usr/bin/brave-browser',
      '/usr/bin/brave-browser-stable',
      '/usr/bin/brave-browser-beta',
      '/usr/bin/brave-browser-nightly',
      '/usr/bin/brave',
      '/opt/brave.com/brave/brave-browser',
      '/opt/brave.com/brave-beta/brave-browser-beta',
      '/opt/brave.com/brave-nightly/brave-browser-nightly',
      '/snap/bin/brave',
      '/var/lib/flatpak/exports/bin/com.brave.Browser',
      pathApi.join(home, '.local/share/flatpak/exports/bin/com.brave.Browser'),
    ];
  }

  return [env.BRAVE_BIN, ...pathCandidates, ...installedCandidates].filter(Boolean);
}

function findBraveBinary(options = {}) {
  const env = options.env || process.env;
  const platform = options.platform || process.platform;
  const home = options.home || os.homedir();
  const isExecutable =
    options.isExecutable ||
    ((candidate) => {
      try {
        fs.accessSync(candidate, fs.constants.X_OK);
        return true;
      } catch {
        return false;
      }
    });

  return braveCandidates({ env, platform, home }).find(isExecutable);
}

function configureBraveChromeBin(env = process.env, options = {}) {
  if (env.CHROME_BIN) return env.CHROME_BIN;
  const braveBinary = findBraveBinary({ ...options, env });
  if (braveBinary) env.CHROME_BIN = braveBinary;
  return braveBinary;
}

module.exports = { braveCandidates, configureBraveChromeBin, findBraveBinary };
