-- The default database 'epistola' is created by POSTGRES_DB and used by the Valtimo test-app.
-- Create a separate database for the Epistola server to avoid Flyway migration conflicts.
CREATE DATABASE epistola_suite;
GRANT ALL PRIVILEGES ON DATABASE epistola_suite TO epistola;