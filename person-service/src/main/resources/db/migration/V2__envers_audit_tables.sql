CREATE TABLE person.addresses_aud
(
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    id         UUID    NOT NULL,
    created    TIMESTAMP WITH TIME ZONE,
    updated    TIMESTAMP WITH TIME ZONE,
    version    BIGINT,
    country_id INTEGER,
    address    VARCHAR(128),
    zip_code   VARCHAR(32),
    archived   TIMESTAMP WITH TIME ZONE,
    city       VARCHAR(64),
    state      VARCHAR(64),
    PRIMARY KEY (rev, id),
    CONSTRAINT fk_addresses_aud_revinfo FOREIGN KEY (rev) REFERENCES person.revinfo (rev)
);

CREATE TABLE person.users_aud
(
    rev            INTEGER NOT NULL,
    revtype        SMALLINT,
    id             UUID    NOT NULL,
    secret_key     VARCHAR(64),
    email          VARCHAR(1024),
    created        TIMESTAMP WITH TIME ZONE,
    updated        TIMESTAMP WITH TIME ZONE,
    version        BIGINT,
    first_name     VARCHAR(64),
    last_name      VARCHAR(64),
    filled         BOOLEAN,
    address_id     UUID,
    email_mod      BOOLEAN,
    first_name_mod BOOLEAN,
    last_name_mod  BOOLEAN,
    filled_mod     BOOLEAN,
    address_mod    BOOLEAN,
    PRIMARY KEY (rev, id),
    CONSTRAINT fk_users_aud_revinfo FOREIGN KEY (rev) REFERENCES person.revinfo (rev)
);

CREATE TABLE person.individuals_aud
(
    rev                 INTEGER NOT NULL,
    revtype             SMALLINT,
    id                  UUID    NOT NULL,
    user_id             UUID,
    version             BIGINT,
    passport_number     VARCHAR(32),
    phone_number        VARCHAR(32),
    verified_at         TIMESTAMP WITH TIME ZONE,
    archived_at         TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(32),
    passport_number_mod BOOLEAN,
    phone_number_mod    BOOLEAN,
    verified_at_mod     BOOLEAN,
    archived_at_mod     BOOLEAN,
    status_mod          BOOLEAN,
    PRIMARY KEY (rev, id),
    CONSTRAINT fk_individuals_aud_revinfo FOREIGN KEY (rev) REFERENCES person.revinfo (rev)
);
