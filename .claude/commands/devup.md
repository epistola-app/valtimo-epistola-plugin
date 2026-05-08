# Dev up

Bring up the test-app backend and frontend locally, ensuring the supporting docker infra is running. See [CLAUDE.md → Development Scenarios](../../CLAUDE.md#development-scenarios) for the underlying scenarios — this command is the "happy path" launcher and corresponds to **Scenario 3** when Epistola is started as a container.

## Goal

After running this command, the user should have:

- `ved-postgres` (5432) and `ved-keycloak` (8081) running in docker
- An Epistola server reachable on `http://localhost:4000`
- The Valtimo backend running on `http://localhost:8080`
- The Valtimo test-app frontend running on `http://localhost:4200`

Backend and frontend must be launched via `Bash` with `run_in_background: true` so logs stream into this session and are killed when the session ends.

## Steps

### 1. Pre-flight

- Confirm `pwd` is `/Users/sdegroot/scm/epistola/valtimo-epistola-plugin` (or chdir there with absolute paths in subsequent commands).
- Confirm `docker info` succeeds. If Docker isn't running, stop and tell the user to start Docker Desktop.
- Confirm `mise` and `pnpm` are on PATH (`./gradlew` is a mise shim — see memory `project_gradlew_mise_shim.md`).

### 2. Ensure docker infra is up

Start (or no-op if already running) the postgres + keycloak services from `docker/docker-compose.yml`:

```bash
docker compose -f docker/docker-compose.yml up -d ved-postgres ved-keycloak
```

Wait until `docker compose -f docker/docker-compose.yml ps ved-postgres` reports `healthy`. Don't poll in a tight loop — use `Monitor` on the compose logs or `until` with a 2s sleep (no leading `sleep`).

### 3. Check Epistola on port 4000

Probe the port:

```bash
lsof -nP -iTCP:4000 -sTCP:LISTEN | tail -n +2
```

- **Already listening** → assume Epistola is up; print which process holds it (so the user can sanity-check) and continue to step 4.
- **Nothing listening** → ask the user via `AskUserQuestion`:
  - "Start Epistola as a container (`docker compose --profile server up -d ved-epistola-server`)" — recommended
  - "I'll start Epistola myself another way" — wait briefly, then re-probe; if still down after one re-probe, continue anyway and warn the user that the Valtimo backend will fail to call Epistola until 4000 is up.

If the user picks "container", run:

```bash
docker compose -f docker/docker-compose.yml --profile server up -d ved-epistola-server
```

Then wait for port 4000 to actually accept connections (`nc -z localhost 4000` in an `until` loop).

### 4. Build the plugin library

This must happen **before** the test-app frontend starts (the test-app consumes the plugin via pnpm `workspace:*`).

```bash
cd frontend/plugin && pnpm install --frozen-lockfile && pnpm build
```

Run in the foreground — the frontend dev server depends on the build output existing. Skip `pnpm install` if `node_modules` already exists and the lockfile hasn't changed (use `git diff --quiet HEAD -- pnpm-lock.yaml` as the gate).

### 5. Start the Valtimo backend (background)

```bash
./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile is required — the default profile references env vars like `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` that aren't set in a plain shell, and bootRun fails fast with `PlaceholderResolutionException`. The `dev` profile supplies sensible localhost defaults.

Launch with `Bash` `run_in_background: true`. Use `Monitor` to tail until you see `Started ApplicationKt in N seconds` (the test-app's Kotlin entrypoint is `com.ritense.valtimo.ApplicationKt` — **not** `ValtimoApplication`). Watchdog with `until grep -qE "Started ApplicationKt|APPLICATION FAILED TO START|BUILD FAILED" log; do sleep 3; done` — never use `head` in the `until` pipeline (its exit code masks grep's). On a warm Gradle daemon, expect ~25–30s.

If a previous backend is still bound to 8080, surface that to the user — don't auto-kill.

### 6. Start the Valtimo frontend (background)

```bash
cd test-app/frontend && pnpm install && pnpm start
```

Launch with `Bash` `run_in_background: true`. Watch for the Angular dev server's "Compiled successfully" / "Local: http://localhost:4200" line via `Monitor`.

### 7. Report

Print a short summary:

- Postgres / Keycloak: ✅
- Epistola on 4000: container | external | ⚠️ not up
- Plugin library: built (size from `frontend/plugin/dist`)
- Backend: PID + log handle
- Frontend: PID + log handle + http://localhost:4200

Mention that the background processes will be killed when the Claude Code session ends.

## Stopping

This command does **not** stop anything. To bring it down, the user can:

```bash
docker compose -f docker/docker-compose.yml --profile server down
```

…and end the Claude session (or kill the background bash handles) for the gradle/pnpm processes.
