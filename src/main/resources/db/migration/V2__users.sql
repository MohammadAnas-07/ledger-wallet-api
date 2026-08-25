-- Users: login identity and credentials.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    -- Stored already lower-cased by the application. Email is case-insensitive in
    -- practice, so without normalisation "A@example.com" and "a@example.com" would
    -- register as two separate accounts and the unique constraint would not stop it.
    email         VARCHAR(320) NOT NULL,
    -- BCrypt output is 60 characters; the extra room avoids a migration if the
    -- algorithm or its parameters ever change.
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Enforces one account per email at the database level, not just in application
-- logic: two concurrent registrations with the same email would both pass an
-- application-side "does this email exist" check.
CREATE UNIQUE INDEX ux_users_email ON users (email);
