CREATE TABLE idee_datatourisme_state (
 department char(2) PRIMARY KEY CHECK (department IN ('67','68')),
 next_url text, run_id uuid, mode text CHECK (mode IN ('full','update')),
 started_at timestamptz, completed_at timestamptz, full_completed_at timestamptz,
 pages bigint NOT NULL DEFAULT 0, objects bigint NOT NULL DEFAULT 0,
 last_error text
);
INSERT INTO idee_datatourisme_state(department) VALUES ('67'),('68');
CREATE TABLE idee_datatourisme_quota (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 next_request_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO idee_datatourisme_quota(singleton) VALUES (true);
CREATE TABLE idee_datatourisme_event (
 uuid uuid PRIMARY KEY,
 outing_id bigint NOT NULL UNIQUE REFERENCES idee_outing(id),
 payload jsonb NOT NULL, imported_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE idee_datatourisme_presence (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid),
 department char(2) NOT NULL REFERENCES idee_datatourisme_state(department),
 seen_run uuid NOT NULL, active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(uuid, department)
);
CREATE TABLE idee_datatourisme_period (
 uuid uuid NOT NULL REFERENCES idee_datatourisme_event(uuid),
 position int NOT NULL, payload jsonb NOT NULL,
 schedule_id bigint REFERENCES idee_schedule(id) ON DELETE SET NULL,
 parse_error text,
 PRIMARY KEY(uuid, position)
);
