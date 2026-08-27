# Form Flow-onderzoek

## Huidige stand

- Een afzonderlijk, preview-vrij testpad is toegevoegd als case **Form Flow voorbeeld**.
  Het doorloopt `Genereer brief` → `Naar bevestiging` → `Doorgaan` en eindigt in de
  gewone taak `Vervolgtaak`.
- De backend en frontend van de testapp zijn lokaal gestart met het bestaande
  Valtimo Compose-profiel:
  - frontend: `http://localhost:4200`
  - backend: `http://localhost:8080` (`/api/v1/ping` geeft `pong`)
  - PostgreSQL: poort 5432
  - Keycloak: poort 8081
- Epistola is bewust niet gestart. Daardoor logt de testapp verwachte
  connection-refused-waarschuwingen voor de collector en catalogussynchronisatie
  op poort 4000. Die raken het preview-vrije Form Flow-voorbeeld niet.
- De configuratie- en formatteringscontrole slaagt:

  ```text
  ./gradlew :test-app:backend:test \
    --tests com.ritense.valtimo.epistola.FormFlowDemoConfigurationTest \
    :test-app:backend:ktlintCheck
  ```

## Wat nog te doen is

1. Doorloop **Form Flow voorbeeld** handmatig in de frontend en controleer dat na
   **Doorgaan** meteen `Vervolgtaak` verschijnt, zonder browser-refresh.
2. Voeg daarna een tweede, verder identieke Form Flow toe met de
   Epistola-documentpreview in de eerste stap.
3. Herhaal de overgang op de tweede flow en verzamel browserconsole-, netwerk- en
   backendlogs rond de klik op **Doorgaan**. Vergelijk die met de preview-vrije
   baseline om te bepalen of een preview-refresh of een in-flight preview-request
   de navigatie beïnvloedt.
4. Reproduceer de eerder falende `DownloadDocumentE2ETest` afzonderlijk met
   volledige Testcontainers-logging. De handmatige Keycloak-container startte
   succesvol; de exacte oorzaak van de Testcontainers-fout is daarom nog niet
   vastgesteld.

## Lokale opstartnotitie

Gebruik de bestaande Compose-stack en start alleen de testapp:

```text
SPRING_PROFILES_ACTIVE=dev ./gradlew :test-app:backend:bootRun
cd test-app/frontend && pnpm start
```

`bootRunWithDocker` is op deze machine niet bruikbaar met de aanwezige
Podman Compose-provider: de Gradle Compose-plugin vraagt `--scale`, dat
Podman Compose 1.6.0 niet ondersteunt. Dit is geen blokkade zolang de bestaande
Valtimo Compose-services draaien.
