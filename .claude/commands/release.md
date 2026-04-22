# Release

Prepare and trigger a release for the valtimo-epistola-plugin.

## Arguments

- `$ARGUMENTS` — Optional version override (e.g., `0.3.1`). If omitted, the next patch version is auto-calculated from existing git tags.

## Steps

### 1. Pre-flight checks

- Confirm we are on `main` branch (or a `release/*` branch)
- Run `git fetch --tags` to ensure we have all remote tags
- Run `git status` to check for uncommitted changes
- If there are uncommitted changes, warn the user and ask how to proceed

### 2. Determine release version

Inspect existing tags:

- List all `v*` release tags sorted by version (`git tag -l 'v*' --sort=-v:refname`)
- Identify the latest release tag (highest semver)

Then determine the target version:

- If `$ARGUMENTS` is provided, use it as the release version
- Otherwise, take the highest existing `v*` tag and increment the patch by 1

Display the version that will be released and the latest existing tag. **Ask the user for confirmation** before proceeding.

### 3. Prepare the CHANGELOG

- Read `CHANGELOG.md`
- If there is content under the `## [Unreleased]` section:
  - Insert a new version heading `## [X.Y.Z] - YYYY-MM-DD` (using today's date) between the Unreleased heading and its content
  - Leave an empty `## [Unreleased]` section at the top for future changes
  - Do NOT remove or reorder any existing content
  - Show the user a summary of the changelog sections being versioned
- If there is NO content under `[Unreleased]`, warn the user and ask if they want to proceed with an empty changelog entry
- Stage `CHANGELOG.md`

### 4. Commit and push

- Create a commit with message: `chore: prepare release X.Y.Z`
- **Ask the user for permission** before pushing
- Push to origin

### 5. Create GitHub Release

Build the release notes body from the changelog section that was just versioned.

Create the GitHub Release using `gh`:

```
gh release create vX.Y.Z --target main --title "vX.Y.Z" --notes "..."
```

This triggers the Release workflow in GitHub Actions which handles building, testing, publishing to Maven Central + npm, building Docker images, and updating the release with install instructions.

### 6. Post-release summary

Display:

- Version released (and previous version for context)
- Artifacts that will be published:
  - Maven Central: `app.epistola.valtimo:epistola-plugin:X.Y.Z`
  - npm: `@epistola.app/valtimo-plugin@X.Y.Z`
  - Docker: `ghcr.io/epistola-app/valtimo/demo-backend:X.Y.Z` and `demo-frontend:X.Y.Z`
  - GitHub Release: `vX.Y.Z`
- Link to monitor: `https://github.com/epistola-app/valtimo-epistola-plugin/actions/workflows/release.yml`

## Important

- NEVER force-push
- NEVER skip the user confirmation steps (version confirmation + push permission)
- The CI pipeline handles all building, testing, publishing, and Docker image creation — this skill only prepares the changelog, creates the commit, and triggers the pipeline via GitHub Release
- If a release fails in CI, it can be re-triggered via workflow_dispatch with the version number
