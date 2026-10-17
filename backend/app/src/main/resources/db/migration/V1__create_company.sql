CREATE TABLE company (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_company_slug UNIQUE (slug),
    CONSTRAINT ck_company_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_company_name CHECK (length(btrim(name)) > 0)
);
