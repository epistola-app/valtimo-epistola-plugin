# Contributing to Valtimo Epistola Plugin

Thank you for your interest in contributing to the Valtimo Epistola Plugin! This document provides guidelines and information for contributors.

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Started

For development setup instructions, see the [README](README.md). In short:

1. Ensure you have Java 21, Node.js 24, and pnpm installed
2. Start local infrastructure: `cd docker && docker compose up -d`
3. Build the frontend plugin: `cd frontend/plugin && pnpm build`
4. Start the test-app backend: `./gradlew :test-app:backend:bootRun`
5. Start the test-app frontend: `cd test-app/frontend && pnpm start`

## How to Contribute

### Reporting Bugs

Found a bug? Please [open an issue](../../issues/new?template=bug_report.yml) with:

- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java/Node version)

### Suggesting Features

Have an idea? [Open a feature request](../../issues/new?template=feature_request.yml) describing:

- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

### Improving Documentation

Documentation improvements are always welcome! [Open a documentation issue](../../issues/new?template=documentation.yml) or submit a PR directly.

### Submitting Code

1. Fork the repository
2. Create a feature branch (see naming conventions below)
3. Make your changes
4. Ensure tests pass
5. Submit a pull request

## Development Workflow

### Branch Naming

Use descriptive branch names with these prefixes:

| Prefix      | Purpose               |
| ----------- | --------------------- |
| `feat/`     | New features          |
| `fix/`      | Bug fixes             |
| `docs/`     | Documentation changes |
| `chore/`    | Maintenance tasks     |
| `refactor/` | Code refactoring      |

**Examples:**

- `feat/add-batch-generation`
- `fix/polling-timeout-bug`
- `docs/update-configuration-guide`

### Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/). This enables automatic semantic versioning.

**Format:** `<type>: <description>`

| Type       | Description                             |
| ---------- | --------------------------------------- |
| `feat`     | New feature                             |
| `fix`      | Bug fix                                 |
| `docs`     | Documentation only                      |
| `chore`    | Maintenance, dependencies               |
| `refactor` | Code change that neither fixes nor adds |
| `test`     | Adding or updating tests                |

**Examples:**

```
feat: add PDF export functionality
fix: resolve polling timeout on large documents
docs: update plugin configuration guide
```

**Breaking Changes:**

- Add `!` after type: `feat!: remove deprecated API`
- Or add `BREAKING CHANGE:` in the commit footer

### Commit Signing

Commits should be signed. To configure SSH commit signing:

1. Go to GitHub > Settings > SSH and GPG keys
2. Click "New SSH key"
3. Select **"Signing Key"** as the key type
4. Paste your public key

Then configure Git:

```bash
git config commit.gpgsign true
git config gpg.format ssh
git config user.signingkey ~/.ssh/id_ed25519.pub
```

### Code Style

#### Java (Backend)

- Standard Java conventions with Lombok where appropriate
- EditorConfig is configured for consistent formatting
- Use `@PluginProperty` keys that exactly match frontend field names

#### Angular (Frontend)

- Follow existing component patterns in `frontend/plugin/src/lib/`
- Add both `nl` and `en` translations in `epistola.specification.ts`
- Use standalone components (Angular 19)

### Testing Requirements

- All PRs must pass CI checks
- New features should include tests
- **Backend:** JUnit 5 with Testcontainers (requires Docker)
- **Frontend:** Add tests for new components/utilities

Run tests locally:

```bash
# Backend
./gradlew :backend:plugin:test

# Frontend
cd frontend/plugin && pnpm test
```

## Pull Request Process

1. **Create your PR** with a clear description
2. **Link related issues** using keywords (e.g., "Closes #123")
3. **Ensure CI passes** - all checks must be green
4. **Wait for review** - maintainers will review your PR
5. **Address feedback** - make requested changes
6. **Get merged!** - once approved, your PR will be merged

### What to Expect

- Initial response within a few days
- Constructive feedback focused on code quality
- Possible requests for changes or clarification
- Appreciation for your contribution!

## Issue Labels

Labels are automatically managed via [`.github/labels.yml`](.github/labels.yml). Key labels include:

| Label                         | Description                |
| ----------------------------- | -------------------------- |
| `bug`                         | Something isn't working    |
| `feature`                     | New feature request        |
| `documentation`               | Documentation improvements |
| `good first issue`            | Good for newcomers         |
| `help wanted`                 | Extra attention needed     |
| `backend`                     | Java/Spring Boot related   |
| `frontend`                    | Angular plugin related     |
| `priority: critical/high/low` | Issue priority             |
| `status: blocked/in progress` | Current status             |

See the [labels config](.github/labels.yml) for the complete list.

## Questions?

For questions and discussions, please use [GitHub Discussions](../../discussions) rather than opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project (EUPL-1.2).

---

Thank you for contributing to the Valtimo Epistola Plugin!
