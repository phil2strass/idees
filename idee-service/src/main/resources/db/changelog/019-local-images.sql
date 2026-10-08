CREATE TABLE idee_image_download (
    source_url text PRIMARY KEY,
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','downloading','ready','retrying')),
    local_url text,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    downloaded_at timestamptz,
    last_error text
);
CREATE INDEX idee_image_download_pending ON idee_image_download(next_attempt_at) WHERE state <> 'ready';
-- Existing imported galleries are picked up too; source URLs and credits remain intact.
INSERT INTO idee_image_download(source_url)
SELECT DISTINCT m.url FROM idee_media m JOIN idee_outing o ON o.id=m.outing_id
WHERE o.source_name='datatourisme' AND m.url ~ '^https?://' ON CONFLICT DO NOTHING;
