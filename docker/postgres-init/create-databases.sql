-- The default database 'epistola' is created by POSTGRES_DB and used by the Valtimo test-app.
-- Additional databases for other services sharing this postgres instance.
CREATE DATABASE epistola_suite;
GRANT ALL PRIVILEGES ON DATABASE epistola_suite TO epistola;

CREATE DATABASE keycloak;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO epistola;