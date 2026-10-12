# Security Policy

## Supported Versions

| Version                                        | Supported |
| ---------------------------------------------- | --------- |
| Two most recently released minor release lines | Yes       |

Security updates are provided for the two most recently released minor release
lines. As long as fewer than two minor release lines have been released, all
available minor release lines are supported.

Which Valtimo and Epistola Suite versions each release works with is recorded in
[`COMPATIBILITY.md`](COMPATIBILITY.md).

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability, please report it responsibly.

### How to Report

**Please use GitHub's private vulnerability reporting feature:**

1. Open the repository's
   [private vulnerability reporting form](https://github.com/epistola-app/valtimo-epistola-plugin/security/advisories/new)
2. Click "Report a vulnerability"
3. Fill out the form with details about the vulnerability

This ensures your report remains private until we can address it.

Do not open a public issue or pull request, and do not push proof-of-concept or
fix commits to a public fork. Public activity can disclose the vulnerability
before users have a patched release available.

A vulnerability in Epistola Suite itself — the server this plugin talks to —
belongs with that project: use the
[Epistola Suite reporting form](https://github.com/epistola-app/epistola-suite/security/advisories/new).
If you are unsure which side a problem is on, report it here and we will route it.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- The plugin, Valtimo and Epistola Suite versions involved
- Any suggested fixes (optional)

### Response Timeline

- **Acknowledgment:** Within 48 hours
- **Initial Assessment:** Within 1 week
- **Resolution Timeline:** Depends on severity and complexity

### What to Expect

1. We will acknowledge your report within 48 hours
2. We will triage the report in a draft GitHub Security Advisory
3. We will develop and review the fix in the advisory's temporary private fork
4. We will test the fix and prepare a patched release before public disclosure
5. We will coordinate disclosure timing and keep you informed of progress
6. We will credit you in the advisory and release notes unless you prefer anonymity

### Scope

This security policy applies to:

- The Valtimo Epistola Plugin backend (`backend/plugin`, published as
  `app.epistola.valtimo:epistola-plugin`)
- The Valtimo Epistola Plugin frontend (`frontend/plugin`, published as
  `@epistola.app/valtimo-plugin`)

### Out of Scope

- Third-party dependencies, including Valtimo itself (please report to upstream maintainers)
- The demo application (`test-app/`), its container images and Helm chart, and
  other development/test environments
- Social engineering attempts

## Security Best Practices

When contributing, please:

- Never commit secrets, credentials, or API keys
- Never log personal data — see [`CLAUDE.md`](CLAUDE.md#never-log-personal-data)
- Follow secure coding practices
- Keep dependencies up to date
- Report any security concerns promptly

Thank you for helping keep the Valtimo Epistola Plugin secure!
