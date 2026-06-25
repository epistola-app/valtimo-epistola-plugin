# Fake ZGW backend (`ved-zgw-mock`) — the default local ZGW

`server.py` is a tiny, dependency-free, **stateful** fake of the ZGW **Documenten + Zaken +
Catalogi** APIs. It's the **default** ZGW backend in `docker-compose.yml` (started by plain
`docker compose up`), so the Epistola subsidy demo can store generated documents on the case
**Documents tab** without running a real OpenZaak — no PostGIS, Redis, Celery, or amd64
emulation, native on arm64/amd64, a few MB of RAM.

## Why a fake

OSS Valtimo has no durable non-ZGW document store, so the case Documents tab is served through
the ZGW APIs. The Epistola plugin never calls ZGW itself — Valtimo's
`documentenapi`/`zakenapi`/`catalogiapi` plugins do, and only hit a small, fixed set of
endpoints. The fake implements exactly those, with in-memory state, served on `:8002` with the
same paths real OpenZaak uses — so the plugin connections (`config/zgw.pluginconfig.json`) and
the subsidy process-link work unchanged. Auth is accepted but not verified (no JWT signing, no
32-char-secret rule).

## Implemented surface

- **Catalogi** — `GET zaaktypen/{uuid}`, `GET informatieobjecttypen/{uuid}` (published,
  matching the UUIDs in `docker/openzaak/fixtures/catalogi.json` and the process-link).
- **Zaken** — `POST zaken` (create-zaak), `GET zaken[/{uuid}]`, `POST zaakinformatieobjecten`
  (link-document-to-zaak), `GET zaakinformatieobjecten?zaak=…` (the Documents tab reads this).
- **Documenten** — `POST enkelvoudiginformatieobjecten` (store-temp-document, stores the
  uploaded bytes), `GET …/{uuid}`, `GET …/{uuid}/download` (serves the PDF).

Response shapes are modeled on real OpenZaak. State is in memory (lost on restart).

## Use

```bash
cd docker
docker compose up -d            # starts ved-zgw-mock on :8002 (+ postgres, keycloak)
```

Run Valtimo (host Gradle bootRun, dev profile) and Epistola, start a **Subsidie Besluitpakket**
case: `create-zaak` → generate (Epistola) → download → `store-temp-document` →
`link-document-to-zaak` all run against the mock, and the case Documents tab lists the PDFs.

## When to use the real OpenZaak instead

For full ZGW conformance testing, use the `openzaak` profile (heavier: its own PostGIS DB +
Redis + Celery + web; amd64-emulated on arm64). The mock validates the Valtimo-side chain and
the demo UX; it is **not** a conformance substitute. See `../openzaak/README.md`.

```bash
docker compose stop ved-zgw-mock          # free :8002
docker compose --profile openzaak up -d
```
