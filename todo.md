# Form Flow-onderzoek

## Huidige stand

- Een afzonderlijk, preview-vrij testpad bestaat als case **Form Flow voorbeeld**.
  Het doorloopt `Genereer brief` → `Naar bevestiging` → `Doorgaan` en eindigt in de
  gewone taak `Vervolgtaak`.
- **Het pad is nu daadwerkelijk doorlopen en werkt.** Dat was eerder niet zo: `Doorgaan`
  gaf een 500.

### Opgeloste blokkade: 500 bij de laatste stap

`valtimoFormFlow.completeTask(additionalProperties, step.submissionData)` — de
variant met twee argumenten — gebruikt standaard `doc:/submission` als opslagpad
en schrijft de submissiegegevens van de afsluitende stap dus naar het dossier.
Het documentschema van de demo stond op `additionalProperties: false` met alleen
`title`, waardoor die schrijfactie strandde op:

```text
extraneous key [submission] is not permitted
```

De taak werd daardoor nooit afgerond en de BPMN-overgang naar `Vervolgtaak` vond
niet plaats. Het schema accepteert nu een `submission`-object.

### Verificatie

- `FormFlowDemoConfigurationTest` legt de koppeling tussen de gekozen
  `completeTask`-variant en het documentschema vast.
- `test-app/frontend/e2e/tests/form-flow-transition.spec.ts` doorloopt de flow in
  de browser en controleert dat `Vervolgtaak` **zonder pagina-refresh** de open
  taak wordt. De test is in beide richtingen gecontroleerd: hij faalt met het
  oude schema en slaagt met het nieuwe.
- Twee valkuilen die tijdens dat controleren naar boven kwamen en in de test zijn
  vastgelegd:
  - `getByText('Vervolgtaak')` is **geen** bruikbare assertie — die tekst staat
    ook in verborgen markup, waardoor de test groen bleef terwijl de flow
    vastliep. De test kijkt daarom naar de zichtbare tekst en naar de rij
    `Vervolgtaak Open` in de takenlijst.
  - `Genereer brief` blijft na een **geslaagde** afronding in beeld als
    bevestigingsmelding. De afwezigheid daarvan zegt dus niets.

### Draaiende omgeving

- frontend: `http://localhost:4200`
- backend: `http://localhost:8080` (`/api/v1/ping` geeft `pong`)
- PostgreSQL: poort 5432, Keycloak: poort 8081

## Wat nog te doen is

1. Voeg een tweede, verder identieke Form Flow toe met de Epistola-documentpreview
   in de eerste stap.
   - Model de service task naar `objection`/`generate-decision`: het
     preview-component wijst met `processDefinitionKey` + `sourceActivityId` naar
     een `generate-document`-proceslink. Zie
     `config/case/objection/1.0.0/form/assess-objection.form.json` voor de vorm,
     inclusief de verplichte `epistolaTaskId`-carrier.
   - `example-template` uit de `municipality-demo`-catalogus verwacht één veld
     (`firstName`) en is daarmee het eenvoudigst bruikbaar.
   - Plaats de service task **na** beide user tasks, zodat de overgang die we
     meten identiek blijft aan de baseline.
2. Herhaal de overgang op de tweede flow en verzamel browserconsole-, netwerk- en
   backendlogs rond de klik op **Doorgaan**. Vergelijk die met de preview-vrije
   baseline. De Playwright-test uit stap 1 is te kopiëren als meetinstrument; de
   allowlists voor console- en netwerkruis staan er al in.
3. Reproduceer de eerder falende `DownloadDocumentE2ETest` afzonderlijk met
   volledige Testcontainers-logging. De handmatige Keycloak-container startte
   succesvol; de exacte oorzaak van de Testcontainers-fout is nog niet
   vastgesteld.

## Aandachtspunten voor de omgeving

- **Het Compose-profiel `server` draait Epistola op poort 4000, niet 4010.**
  `CLAUDE.md` noemt 4010 voor dit profiel; `docker/docker-compose.yml` is
  leidend. 4010 is het `mock`-profiel.
- Een lokaal aanwezige `ghcr.io/epistola-app/epistola-suite:latest` kan verouderd
  zijn. De hier aangetroffen image was gelabeld `0.17.0`; het
  `/templates`-antwoord mist dan `page`, dat de vastgezette contractclient
  (`1.1.0`) als non-nullable verwacht, met een 500 op het ophalen van templates
  tot gevolg. `docker pull` haalde `1.1.0` op, wat wél overeenkomt met de pin in
  `gradle/libs.versions.toml` en met `COMPATIBILITY.md`. Controleer bij
  preview-onderzoek dus eerst het versielabel van de image.
- `bootRunWithDocker` is op deze machine niet bruikbaar met de aanwezige
  Podman Compose-provider: de Gradle Compose-plugin vraagt `--scale`, dat
  Podman Compose 1.6.0 niet ondersteunt. Geen blokkade zolang de bestaande
  Valtimo Compose-services draaien.

## Lokale opstartnotitie

```text
SPRING_PROFILES_ACTIVE=dev ./gradlew :test-app:backend:bootRun
cd test-app/frontend && pnpm start
```

De Playwright-baseline draaien (backend + frontend moeten draaien):

```text
cd test-app/frontend
pnpm exec playwright test --config=e2e/playwright.config.ts form-flow-transition
```
