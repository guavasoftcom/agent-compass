-- Span names (e.g. tool-call spans with long inputs) and status messages
-- (error strings) can exceed 255 characters.  Widen to text; scope_name
-- is widened proactively for the same reason.
ALTER TABLE spans
    ALTER COLUMN name         TYPE text,
    ALTER COLUMN status_message TYPE text,
    ALTER COLUMN scope_name   TYPE text;
