# Release Helm Chart

Prepare and trigger a Helm chart release for the valtimo-demo chart.

## Arguments

- `$ARGUMENTS` — Optional version override (e.g., `0.3.0`). If omitted, the next version is determined from existing `chart-*` tags.

## Steps

### 1. Pre-flight checks

- Confirm we are on `main` branch
- Run `git fetch --tags` to ensure we have all remote tags
- Run `git status` to check for uncommitted changes
- If there are uncommitted changes, warn the user and ask how to proceed

### 2. Determine chart version

Inspect existing tags:

- List all `chart-*` tags sorted by version (`git tag -l 'chart-*' --sort=-v:refname`)
- Identify the latest chart release tag

Then determine the target version:

- If `$ARGUMENTS` is provided, use it as the chart version
- Otherwise, read the major.minor from `charts/valtimo-demo/Chart.yaml` and find the next patch version based on existing `chart-*` tags

Display the version that will be released and the latest existing chart tag. **Ask the user for confirmation** before proceeding.

### 3. Prepare the CHANGELOG

- Read `CHANGELOG.md`
- If there is chart-related content under `## [Unreleased]`, confirm with the user that the changelog is up to date
- If not already done, stage and commit any changelog updates with message: `chore: prepare chart release X.Y.Z`
- **Ask the user for permission** before pushing

### 4. Push and create GitHub Release

Build the release notes body from the changelog.

Create the GitHub Release using `gh`:

```
gh release create chart-X.Y.Z --target main --title "Helm Chart X.Y.Z" --notes "..."
```

This triggers the Helm Chart workflow in GitHub Actions which handles:
- Writing the version into Chart.yaml
- Syncing appVersion with the latest app release tag
- Linting, packaging, and pushing to `oci://ghcr.io/epistola-app/charts/valtimo-demo`
- Updating the GitHub Release with install/upgrade instructions

### 5. Post-release summary

Display:
- Chart version released (and previous version for context)
- OCI registry: `oci://ghcr.io/epistola-app/charts/valtimo-demo:X.Y.Z`
- Install command: `helm install valtimo-demo oci://ghcr.io/epistola-app/charts/valtimo-demo --version X.Y.Z`
- Link to monitor: `https://github.com/epistola-app/valtimo-epistola-plugin/actions/workflows/helm.yml`

## Important

- NEVER force-push
- NEVER skip the user confirmation steps (version confirmation + push permission)
- The CI pipeline handles all packaging and publishing — this skill only prepares the changelog and triggers the pipeline via GitHub Release
- If a release fails in CI, it can be re-triggered via workflow_dispatch with the version number
- Chart tags use the `chart-` prefix (e.g., `chart-0.3.0`), NOT `v` prefix
