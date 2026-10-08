ALTER TABLE idee_media
  ADD COLUMN is_primary boolean NOT NULL DEFAULT false;

-- Preserve a deterministic cover if an older installation already has media.
UPDATE idee_media m
SET is_primary = true
WHERE m.id IN (
  SELECT DISTINCT ON (outing_id) id
  FROM idee_media
  ORDER BY outing_id, position, id
);

CREATE UNIQUE INDEX idee_media_one_primary_idx
  ON idee_media(outing_id) WHERE is_primary;
CREATE INDEX idee_media_outing_order_idx
  ON idee_media(outing_id, is_primary DESC, position, id);
