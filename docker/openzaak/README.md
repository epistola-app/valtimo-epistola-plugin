# OpenZaak (ZGW) — opt-in `openzaak` docker profile

This profile stands up a real [OpenZaak](https://open-zaak.readthedocs.io/) for full-fidelity ZGW
testing, so generated Epistola documents can be archived durably and shown on a case's
**Documents** tab. It is **opt-in** — plain `docker compose up` runs the lightweight
`ved-zgw-mock` instead (see `../zgw-fake/README.md`); use this profile only when you need the real
thing.

OSS Valtimo has no durable non-ZGW document store (its `LocalResourceService` is a stub), so the
case Documents tab is served through the ZGW Documenten/Zaken APIs. The Epistola plugin reaches
them by composition (no plugin code): `epistola-download-document` (→ temporary resource) →
`documentenapi:store-temp-document` → `zakenapi:link-document-to-zaak`.

## Footprint

The "whole thing", self-contained: its own PostGIS DB (`ved-openzaak-db`, multi-arch
`imresamu/postgis`) + a small Redis + two OpenZaak containers (web + one Celery worker). The
default `ved-postgres` stays lean (`postgres:16-alpine`). Notifications are disabled, so no Open
Notificaties is required.

> OpenZaak publishes amd64 images only; on Apple Silicon they run under emulation
> (`platform: linux/amd64`) — slow first boot and memory-hungry, so a small Docker/podman VM
> (≈2 GB) may not hold OpenZaak alongside the rest of the stack. On amd64 (CI/cluster) it runs
> natively. The lightweight mock avoids all of this for everyday local use.

## Run

```bash
cd docker
docker compose stop ved-zgw-mock          # free :8002 (mock and OpenZaak both bind it)
docker compose --profile openzaak up -d
# First boot pulls ~1GB and runs migrations + provisioning; wait for ved-openzaak to be healthy:
docker compose --profile openzaak ps
```

OpenZaak is then at http://localhost:8002 (admin: `admin` / `admin`).

## Provisioning (automatic, via `ved-openzaak-init`)

- `setup_configuration/data.yaml` — a `valtimo` API client (client id `valtimo`, a ≥32-char
  secret — OpenZaak signs its API JWT with it) with full authorizations; sites; notifications
  disabled.
- `fixtures/catalogi.json` — a published catalogus + zaaktype + informatieobjecttype (derived from
  OpenZaak's demo fixture with `concept` flipped to `false` so they are usable via the API).

The Valtimo `openzaak`/`documentenapi`/`zakenapi`/`catalogiapi` plugin connections
(`config/zgw.pluginconfig.json`) and the subsidy process-link reference these by URL/credentials,
overridable per environment via `ZGW_*` env vars (defaults target this local stack).

## End-to-end test

With this profile up, Epistola running (`:4000`, on the host) and the Valtimo backend running
against `ved-postgres` (dev profile, `:5432`), start a **Subsidie Besluitpakket** case. The process
creates a zaak, generates 3 PDFs, stores them in the Documenten API and links them to the zaak —
they then appear on the case **Documents** tab. (For everyday local runs the default `ved-zgw-mock`
gives the same result without OpenZaak's weight.)
