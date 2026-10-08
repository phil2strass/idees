-- Version the selection so existing installations automatically perform one full enrichment.
ALTER TABLE idee_datatourisme_state ADD COLUMN selection_version integer NOT NULL DEFAULT 0;
ALTER TABLE idee_datatourisme_event
 ADD COLUMN selection_version integer NOT NULL DEFAULT 0,
 ADD COLUMN source_identifier text,
 ADD COLUMN organization_identifier text,
 ADD COLUMN source_created_on date,
 ADD COLUMN source_updated_on date,
 ADD COLUMN source_updated_at timestamptz,
 ADD COLUMN obsolete boolean NOT NULL DEFAULT false;
CREATE INDEX idee_dt_source_identifier_idx ON idee_datatourisme_event(organization_identifier,source_identifier);
CREATE INDEX idee_dt_source_updated_idx ON idee_datatourisme_event(source_updated_at);

CREATE TABLE idee_datatourisme_translation (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid) ON DELETE CASCADE,
 language text NOT NULL, title text, summary text, description text, comment text,
 PRIMARY KEY(uuid,language)
);
CREATE TABLE idee_datatourisme_contact (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid) ON DELETE CASCADE,
 role text NOT NULL CHECK(role IN ('contact','booking','creator','publisher','owner','administrative','communication')),
 position integer NOT NULL CHECK(position>=0), name text, payload jsonb NOT NULL,
 UNIQUE(uuid,role,position)
);
CREATE TABLE idee_datatourisme_contact_channel (
 contact_id bigint NOT NULL REFERENCES idee_datatourisme_contact(id) ON DELETE CASCADE,
 kind text NOT NULL CHECK(kind IN ('email','telephone','fax','homepage')),
 value text NOT NULL,
 PRIMARY KEY(contact_id,kind,value)
);
CREATE INDEX idee_dt_contact_channel_lookup_idx ON idee_datatourisme_contact_channel(kind,value);
CREATE TABLE idee_datatourisme_term (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid) ON DELETE CASCADE,
 property text NOT NULL, position integer NOT NULL CHECK(position>=0),
 term_key text, labels jsonb NOT NULL DEFAULT '{}'::jsonb, payload jsonb NOT NULL,
 PRIMARY KEY(uuid,property,position)
);
CREATE INDEX idee_dt_term_lookup_idx ON idee_datatourisme_term(property,term_key);
CREATE TABLE idee_datatourisme_location (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid) ON DELETE CASCADE,
 position integer NOT NULL CHECK(position>=0), address_position integer NOT NULL CHECK(address_position>=0),
 name text, street_address text, postal_code text, city text, insee_code text,
 department_code text, latitude double precision, longitude double precision,
 payload jsonb NOT NULL,
 PRIMARY KEY(uuid,position,address_position),
 CHECK(latitude BETWEEN -90 AND 90), CHECK(longitude BETWEEN -180 AND 180)
);
CREATE INDEX idee_dt_location_city_idx ON idee_datatourisme_location(insee_code);
CREATE INDEX idee_dt_location_department_idx ON idee_datatourisme_location(department_code,postal_code);
CREATE TABLE idee_datatourisme_resource (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid) ON DELETE CASCADE,
 role text NOT NULL CHECK(role IN ('main','representation')),
 representation_position integer NOT NULL CHECK(representation_position>=0),
 resource_position integer NOT NULL CHECK(resource_position>=0),
 locator_position integer NOT NULL CHECK(locator_position>=0),
 url text NOT NULL, media_type text, is_image boolean NOT NULL,
 title text, credit text, license text, payload jsonb NOT NULL,
 PRIMARY KEY(uuid,role,representation_position,resource_position,locator_position)
);
CREATE INDEX idee_dt_resource_url_idx ON idee_datatourisme_resource(md5(url));
