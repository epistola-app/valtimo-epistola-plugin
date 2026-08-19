// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Karma configuration file, see link for more information
// https://karma-runner.github.io/1.0/config/configuration-file.html

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function findBraveBinary() {
  const home = os.homedir();
  const pathDirectories = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  const braveExecutableNames = [
    'brave-browser',
    'brave-browser-stable',
    'brave-browser-beta',
    'brave-browser-nightly',
    'brave',
    'brave.exe',
  ];
  const windowsRoots = [
    process.env.LOCALAPPDATA,
    process.env.PROGRAMFILES,
    process.env['PROGRAMFILES(X86)'],
  ].filter(Boolean);
  const candidates = [
    process.env.BRAVE_BIN,
    ...pathDirectories.flatMap((directory) =>
      braveExecutableNames.map((executable) => path.join(directory, executable)),
    ),
    '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',
    path.join(home, 'Applications/Brave Browser.app/Contents/MacOS/Brave Browser'),
    '/Applications/Brave Browser Beta.app/Contents/MacOS/Brave Browser Beta',
    path.join(home, 'Applications/Brave Browser Beta.app/Contents/MacOS/Brave Browser Beta'),
    '/Applications/Brave Browser Nightly.app/Contents/MacOS/Brave Browser Nightly',
    path.join(home, 'Applications/Brave Browser Nightly.app/Contents/MacOS/Brave Browser Nightly'),
    '/usr/bin/brave-browser',
    '/usr/bin/brave-browser-beta',
    '/usr/bin/brave-browser-nightly',
    '/usr/bin/brave',
    '/opt/brave.com/brave/brave-browser',
    '/opt/brave.com/brave-beta/brave-browser-beta',
    '/opt/brave.com/brave-nightly/brave-browser-nightly',
    '/snap/bin/brave',
    '/var/lib/flatpak/exports/bin/com.brave.Browser',
    path.join(home, '.local/share/flatpak/exports/bin/com.brave.Browser'),
    ...windowsRoots.flatMap((root) => [
      path.join(root, 'BraveSoftware/Brave-Browser/Application/brave.exe'),
      path.join(root, 'BraveSoftware/Brave-Browser-Beta/Application/brave.exe'),
      path.join(root, 'BraveSoftware/Brave-Browser-Nightly/Application/brave.exe'),
    ]),
  ];
  return candidates.find((candidate) => candidate && fs.existsSync(candidate));
}

if (!process.env.CHROME_BIN) {
  const braveBinary = findBraveBinary();
  if (braveBinary) {
    process.env.CHROME_BIN = braveBinary;
  }
}

module.exports = function (config) {
  const isCi = process.env.CI === 'true';
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage-istanbul-reporter'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      clearContext: false, // leave Jasmine Spec Runner output visible in browser
    },
    coverageIstanbulReporter: {
      dir: require('path').join(__dirname, '../coverage'),
      reports: ['html', 'lcovonly', 'text-summary'],
      fixWebpackSourcePaths: true,
    },
    reporters: ['progress', 'kjhtml'],
    failOnEmptyTestSuite: false,
    port: 9876,
    colors: true,
    logLevel: config.LOG_INFO,
    autoWatch: true,
    browsers: [isCi ? 'ChromeHeadless' : 'Chrome'],
    singleRun: isCi,
  });
};
