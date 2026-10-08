-- Internal day bounds support date searches even when the source omits a time.
-- API flags prevent presenting those bounds as communicated opening hours.
ALTER TABLE idee_schedule ADD COLUMN start_time_known boolean NOT NULL DEFAULT true;
ALTER TABLE idee_schedule ADD COLUMN end_time_known boolean NOT NULL DEFAULT true;
