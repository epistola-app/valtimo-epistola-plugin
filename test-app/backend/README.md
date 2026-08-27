# Getting started

## Prerequisites

- Java 21
- A running Keycloak instance
- A running Postgresql server

## Configuration

- Add a properties file to the project root. This file should have the name ".env.properties"
  - The .env.properties.example file can be used as starting point

## Starting up

- Run the following command from a terminal in the project root: `./gradlew bootrun`
- After the Valtimo backend application has finished starting up, the service is available at http://localhost:8080
  - The application uptime can be verified by calling [this API endpoint](http://localhost:8080/api/v1/ping)

## Supporting containers

The Valtimo backend application requires a Keycloak instance and a database server. When running the application locally, running Keycloak and the database locally as well is recommended. In the [valtimo-docker-compose repository](https://github.com/valtimo-platform/valtimo-docker-compose), Docker compose files are available to support the Valtimo application. This repository includes a guide on which Compose file to use.

# Adding implementation code

## Form Flow-demo

De testapp bevat de zelfstandige case **Form Flow voorbeeld**. Deze is een preview-vrije
baseline voor het onderzoeken van taakovergangen in Valtimo Form Flows:

1. Start een nieuw dossier via **Form Flow voorbeeld**.
2. Open de taak **Genereer brief**.
3. Vul de briefgegevens in, kies **Naar bevestiging** en kies daarna **Doorgaan**.
4. De gewone taak **Vervolgtaak** moet zonder browser-refresh verschijnen.

De Form Flow bevat bewust geen Epistola-component of preview-request. Daardoor is dit een
controlepad voor Form Flow zelf; een latere variant met documentpreview kan hiermee vergeleken
worden.
