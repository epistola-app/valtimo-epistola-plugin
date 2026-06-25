-- Enable PostGIS in the OpenZaak database (GeoDjango requires it). Runs once on a fresh
-- volume, connected to POSTGRES_DB (openzaak).
CREATE EXTENSION IF NOT EXISTS postgis;
