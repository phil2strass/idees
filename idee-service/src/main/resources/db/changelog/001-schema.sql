CREATE TABLE idee_category (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 parent_id bigint REFERENCES idee_category(id),
 slug varchar(100) NOT NULL UNIQUE, name varchar(120) NOT NULL, icon varchar(40) NOT NULL DEFAULT 'compass',
 CHECK (parent_id IS DISTINCT FROM id)
);
CREATE TABLE idee_place (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name varchar(200) NOT NULL, address text, postal_code varchar(10), city varchar(120) NOT NULL,
 department char(2) NOT NULL CHECK (department IN ('67','68')),
 latitude numeric(9,6) CHECK (latitude BETWEEN -90 AND 90), longitude numeric(9,6) CHECK (longitude BETWEEN -180 AND 180),
 accessibility text, transport_info text
);
CREATE TABLE idee_organizer (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name varchar(200) NOT NULL, website text, email text, phone varchar(40)
);
CREATE TABLE idee_outing (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 slug varchar(180) NOT NULL UNIQUE, title varchar(220) NOT NULL, summary text NOT NULL, description text NOT NULL,
 kind varchar(20) NOT NULL CHECK (kind IN ('event','permanent')),
 status varchar(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','archived')),
 place_id bigint REFERENCES idee_place(id), organizer_id bigint REFERENCES idee_organizer(id),
 environment varchar(20) NOT NULL DEFAULT 'mixed' CHECK (environment IN ('indoor','outdoor','mixed')),
 min_age smallint CHECK (min_age >= 0), max_age smallint, duration_minutes int CHECK (duration_minutes > 0),
 booking_url text, website text, booking_required boolean NOT NULL DEFAULT false,
 accessibility text, practical_info text, source_url text, verified_at timestamptz,
 is_demo boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (max_age IS NULL OR max_age >= COALESCE(min_age,0))
);
CREATE INDEX idee_outing_status_idx ON idee_outing(status, kind);
CREATE TABLE idee_outing_category (
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 category_id bigint NOT NULL REFERENCES idee_category(id), PRIMARY KEY (outing_id,category_id)
);
CREATE TABLE idee_tag (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, slug varchar(100) NOT NULL UNIQUE, name varchar(120) NOT NULL
);
CREATE TABLE idee_outing_tag (
 outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 tag_id bigint NOT NULL REFERENCES idee_tag(id), PRIMARY KEY (outing_id,tag_id)
);
CREATE TABLE idee_media (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 url text NOT NULL, alt text NOT NULL, credit text, license text, position int NOT NULL DEFAULT 0
);
CREATE TABLE idee_price (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 label varchar(120) NOT NULL, amount numeric(10,2), currency char(3) NOT NULL DEFAULT 'EUR',
 price_type varchar(20) NOT NULL CHECK (price_type IN ('free','fixed','from','donation','unknown')),
 conditions text, valid_from date, valid_until date,
 CHECK (amount IS NULL OR amount >= 0), CHECK (price_type <> 'free' OR amount = 0),
 CHECK (price_type NOT IN ('fixed','from') OR amount IS NOT NULL),
 CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until >= valid_from)
);
-- Une fiche possède autant de calendriers que de saisons/créneaux nécessaires.
-- Les heures de définition sont LOCALES ; les occurrences calculées sont des instants UTC.
CREATE TABLE idee_schedule (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 label varchar(200) NOT NULL, timezone varchar(80) NOT NULL DEFAULT 'Europe/Paris',
 starts_local timestamp NOT NULL, ends_local timestamp NOT NULL,
 all_day boolean NOT NULL DEFAULT false, rrule text,
 place_id bigint REFERENCES idee_place(id), enabled boolean NOT NULL DEFAULT true,
 CHECK (ends_local > starts_local),
 CHECK (NOT all_day OR (starts_local::time = '00:00' AND ends_local::time = '00:00')),
 CHECK (rrule IS NULL OR rrule ~ '^FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;|$)')
);
CREATE INDEX idee_schedule_outing_idx ON idee_schedule(outing_id);
-- Dates supplémentaires (RDATE), exclusions (EXDATE), annulations et reports.
-- original_start_local identifie la séance, même si elle est déplacée.
CREATE TABLE idee_schedule_exception (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, schedule_id bigint NOT NULL REFERENCES idee_schedule(id) ON DELETE CASCADE,
 original_start_local timestamp NOT NULL,
 action varchar(20) NOT NULL CHECK (action IN ('exclude','cancel','override','include')),
 starts_local timestamp, ends_local timestamp, place_id bigint REFERENCES idee_place(id), note text,
 UNIQUE (schedule_id,original_start_local),
 CHECK ((action IN ('override','include') AND starts_local IS NOT NULL AND ends_local IS NOT NULL AND ends_local > starts_local)
 OR (action IN ('exclude','cancel') AND starts_local IS NULL AND ends_local IS NULL AND place_id IS NULL))
);
CREATE TABLE idee_occurrence (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, schedule_id bigint NOT NULL REFERENCES idee_schedule(id) ON DELETE CASCADE,
 original_start_local timestamp NOT NULL, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','cancelled','rescheduled')),
 place_id bigint REFERENCES idee_place(id), note text, CHECK (ends_at > starts_at),
 UNIQUE (schedule_id,original_start_local)
);
CREATE INDEX idee_occurrence_range_idx ON idee_occurrence USING gist (tstzrange(starts_at, ends_at, '[)'));
CREATE INDEX idee_occurrence_schedule_idx ON idee_occurrence(schedule_id,starts_at);
-- Horaires des lieux permanents : saison + jour ISO ; plusieurs lignes pour une coupure à midi.
CREATE TABLE idee_opening_hours (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 weekday smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7), opens_at time NOT NULL, closes_at time NOT NULL,
 closes_next_day boolean NOT NULL DEFAULT false, valid_from date NOT NULL, valid_until date NOT NULL,
 CHECK (valid_until >= valid_from), CHECK (closes_next_day OR closes_at > opens_at)
);
CREATE TABLE idee_opening_exception (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outing_id bigint NOT NULL REFERENCES idee_outing(id) ON DELETE CASCADE,
 day date NOT NULL, closed boolean NOT NULL DEFAULT true, note text,
 UNIQUE (outing_id, day)
);
-- Une exception ouverte peut contenir plusieurs créneaux.
CREATE TABLE idee_opening_exception_slot (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 exception_id bigint NOT NULL REFERENCES idee_opening_exception(id) ON DELETE CASCADE,
 opens_at time NOT NULL, closes_at time NOT NULL, closes_next_day boolean NOT NULL DEFAULT false,
 CHECK (closes_next_day OR closes_at > opens_at)
);
CREATE TABLE idee_calendar_projection (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 from_date date NOT NULL, until_date date NOT NULL, generated_at timestamptz NOT NULL DEFAULT now(),
 CHECK (until_date > from_date)
);
