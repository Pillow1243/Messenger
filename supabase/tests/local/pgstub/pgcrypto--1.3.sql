-- Subset of pgcrypto used by the Messenger migrations, implemented with core PostgreSQL
-- functions. Only needs to be functionally equivalent for *tests*: `gen_random_uuid()` is a
-- built-in since PostgreSQL 13 and `gen_random_bytes(n)` only backs channel invite codes.
create or replace function gen_random_bytes(n integer) returns bytea
language sql volatile as $$
  select substring(
           decode(
             (select string_agg(replace(gen_random_uuid()::text, '-', ''), '')
                from generate_series(1, (n + 15) / 16)),
             'hex'),
           1, n);
$$;
