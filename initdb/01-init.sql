-- Ensure the database exists (POSTGRES_DB env var handles creation,
-- but this is a safety net for pre-existing volumes).
-- This script runs as the POSTGRES_USER against the POSTGRES_DB.

CREATE EXTENSION IF NOT EXISTS vector;
