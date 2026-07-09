CREATE SCHEMA IF NOT EXISTS person;

CREATE TABLE person.countries
(
    id      SERIAL PRIMARY KEY,
    created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name    VARCHAR(32)              NOT NULL,
    alpha2  VARCHAR(2)               NOT NULL,
    alpha3  VARCHAR(3)               NOT NULL,
    status  VARCHAR(32)              NOT NULL,
    CONSTRAINT uk_countries_alpha2 UNIQUE (alpha2),
    CONSTRAINT uk_countries_alpha3 UNIQUE (alpha3)
);

CREATE TABLE person.addresses
(
    id         UUID PRIMARY KEY,
    created    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated    TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL DEFAULT 0,
    country_id INTEGER                  NOT NULL REFERENCES person.countries (id),
    address    VARCHAR(128)             NOT NULL,
    zip_code   VARCHAR(32),
    archived   TIMESTAMP WITH TIME ZONE NULL,
    city       VARCHAR(64)              NOT NULL,
    state      VARCHAR(64)
);

CREATE INDEX idx_addresses_country_id ON person.addresses (country_id);

CREATE TABLE person.users
(
    id         UUID PRIMARY KEY,
    secret_key VARCHAR(64),
    email      VARCHAR(1024)            NOT NULL,
    created    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated    TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL DEFAULT 0,
    first_name VARCHAR(64)              NOT NULL,
    last_name  VARCHAR(64)              NOT NULL,
    filled     BOOLEAN                  NOT NULL DEFAULT FALSE,
    address_id UUID                     NOT NULL REFERENCES person.addresses (id)
);

CREATE UNIQUE INDEX uk_users_email_lower ON person.users (lower(email));
CREATE INDEX idx_users_address_id ON person.users (address_id);

CREATE TABLE person.individuals
(
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL UNIQUE REFERENCES person.users (id),
    version         BIGINT      NOT NULL DEFAULT 0,
    passport_number VARCHAR(32),
    phone_number    VARCHAR(32),
    verified_at     TIMESTAMP WITH TIME ZONE NULL,
    archived_at     TIMESTAMP WITH TIME ZONE NULL,
    status          VARCHAR(32) NOT NULL
);

CREATE INDEX idx_individuals_user_id ON person.individuals (user_id);

CREATE SEQUENCE person.revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE person.revinfo
(
    rev      INTEGER PRIMARY KEY,
    revtstmp BIGINT
);

INSERT INTO person.countries(created, updated, name, alpha2, alpha3, status)
VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'Russia', 'RU', 'RUS', 'ACTIVE'),
       (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'United States', 'US', 'USA', 'ACTIVE');
