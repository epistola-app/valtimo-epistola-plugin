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

1. **Vergelijk de twee flows in de browser.** De preview-variant bestaat en werkt; wat
   nog ontbreekt is de meting waar het onderzoek om draait. Verzamel browserconsole-,
   netwerk- en backendlogs rond de klik op **Doorgaan** in
   **Form Flow voorbeeld met preview** en zet die naast de baseline. Let specifiek op
   een preview-request die nog loopt op het moment dat de taak wordt afgerond:
   auto-refresh staat standaard aan, met 1500 ms debounce en een flush bij blur — het
   verlaten van het onderwerpveld vlak voor de klik is dus precies het scenario dat
   een request in de lucht kan hebben.
   `e2e/tests/form-flow-transition.spec.ts` is als meetinstrument te kopiëren; de
   allowlists voor console- en netwerkruis staan er al in.
2. Reproduceer de eerder falende `DownloadDocumentE2ETest` afzonderlijk met
   volledige Testcontainers-logging. **Let op:** die test is inmiddels groen
   (7 tests) in een volledige `./gradlew :test-app:backend:test`-run, dus deze
   taak is mogelijk achterhaald. De oorspronkelijke fout is niet gereproduceerd,
   dus de oorzaak is nog steeds niet vastgesteld.

### De drie varianten

| Case-proces                    | Preview                      | Waarvoor                                                   |
| ------------------------------ | ---------------------------- | ---------------------------------------------------------- |
| `form-flow-demo`               | geen                         | controle: Form Flow zonder Epistola                        |
| `form-flow-demo-preview`       | op stap 1 (invoerstap)       | preview is bij het klikken op **Doorgaan** al weg          |
| `form-flow-demo-preview-step2` | op stap 2 (bevestigingsstap) | preview staat **in beeld** bij het klikken op **Doorgaan** |

De derde variant is het interessantst voor de hypothese: alleen daar kan een preview-request
nog lopen op het moment dat de taak wordt afgerond. Op stap 1 is de preview op dat moment al
uit beeld.

Twee dingen om te weten bij het meten op stap 2:

- De bevestigingsstap heeft **geen invoerveld**, dus auto-refresh vuurt daar niet. Het venster
  waarin een request loopt is het **initiële laden** van de preview: openen van stap 2 en
  meteen op **Doorgaan** klikken. Wil je ook de auto-refresh op stap 2 meten, dan moet er een
  invoerveld bij in `confirm-letter-with-preview.form.json` plus een `overrideMapping`.
- De step-2-preview heeft bewust geen `overrideMapping`: op die stap is er geen formulierveld om
  uit te lezen, en zonder mapping vuurt de preview meteen bij het openen in plaats van te wachten
  op formulierdata.

### Kan de preview velden van een andere stap gebruiken?

Ja, maar niet vanzelf. `$form` is de data van **het formulier van de huidige stap**; er is geen
aparte bak met alle stappen. Om een veld van een eerdere stap te lezen moet je op de latere stap
een component met **dezelfde key** opnieuw declareren (een `hidden` veld volstaat). Valtimo prefilt
elk stapformulier met de samengevoegde submissiedata van de flow
(`FormFlowInstance.getSubmissionDataContext()` → `FormDefinition.preFill`), zodat die component met
de eerdere waarde in `defaultValue` binnenkomt en Formio die in `root.data` zet — waar `$form` naar
kijkt.

Zonder die carrier is `$form.<key>` simpelweg undefined en valt de mapping stil terug op de
casegegevens. Dat is precies wat `confirm-letter-with-preview.form.json` nu doet met het verborgen
`subject`-veld, en `FormFlowDemoConfigurationTest` bewaakt dat de gelezen keys op beide stappen
bestaan.

Twee praktische gevolgen:

- Latere stappen overschrijven eerdere bij gelijke keys, en de submissiedata van de flow wint van
  document- en procesvariabele-prefill voor dezelfde component.
- Een **verborgen** carrier vuurt geen `change` of `focusout`, dus auto-refresh pakt hem niet op;
  de initiële preview-berekening doet dat wel. Wil je op stap 2 ook auto-refresh meten, dan is een
  zichtbaar invoerveld nodig.

## Bevindingen tot nu toe

- **Aan de backendkant gedraagt de preview-variant zich identiek aan de baseline.**
  Via de REST-API doorlopen beide flows hun twee stappen en wordt `Vervolgtaak`
  de open taak. Als er een probleem is, zit het dus in de browser en niet in de
  BPMN- of Form Flow-afhandeling.
- De preview zelf werkt tegen een draaiende Epistola: `POST /preview` geeft
  `200 application/pdf` (~43 kB), en `inputOverrides` worden toegepast — twee
  runs met verschillende overridewaarden leveren aantoonbaar verschillende
  documenten op. De preview volgt dus wat er in het formulier staat.
- Let op het onderscheid in `PreviewRequest`: `inputOverrides` is de
  `{doc, pv}`-overlay die vóór de JSONata-mapping wordt toegepast (dat is wat het
  component via `overrideMapping` stuurt). `overrides` is iets anders: dat werkt
  ná de mapping op de templatevelden.

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
