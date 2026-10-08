-- One immutable, descriptive filename per image content, shared by all source URLs.
CREATE TABLE idee_image_file (
    content_hash varchar(64) PRIMARY KEY CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    filename text NOT NULL UNIQUE
);
-- Existing files are renamed by the image worker without downloading them again.
-- The old hash-only URLs remain aliases resolved through this registry.
