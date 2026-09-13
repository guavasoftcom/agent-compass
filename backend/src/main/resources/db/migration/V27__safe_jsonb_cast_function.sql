-- tool_input (and MCP's tool_parameters) are JSON-encoded strings inside the attributes
-- jsonb column, and every aggregation that reads a field out of them re-parses that
-- string with a second ::jsonb cast. That cast fails the WHOLE query -- not just the
-- one row -- whenever the string is not valid JSON, and tool_input in particular can
-- carry arbitrary agent-authored content (a Read/Edit/Write payload is literally source
-- code, a Bash payload is literally a shell command), so it is not safe to assume the
-- string always parses. Two independent failure shapes have already been observed on
-- real data: a Java char literal containing the JSON null-character escape sequence
-- (backslash, 'u', four zero digits), which Postgres accepts as syntactically valid JSON
-- but then refuses to convert to text ("unsupported Unicode escape sequence"); and a grep
-- command containing a backslash immediately followed by a pipe character, which is not
-- a legal JSON escape sequence at all ("invalid input syntax for type json"). Stripping
-- each bad sequence as it is discovered does not scale -- it is a blocklist against
-- arbitrary text agents can produce, and the next one is only a different tool call away.
--
-- safe_jsonb() attempts the cast and returns NULL on ANY parse failure rather than
-- failing the query, the same "not recorded" semantics NULL already carries everywhere
-- else these fields are read (a Read call with no file_path, a Bash call with no
-- tool_input at all). IMMUTABLE is valid because the result depends only on the input
-- text -- no side effects, no external state -- and holds even on the exception path,
-- since the same malformed input always fails the same way.
--
-- PARALLEL UNSAFE, not SAFE: the EXCEPTION block below needs a subtransaction to roll
-- back to on a parse failure, and Postgres cannot open a subtransaction inside a
-- parallel worker ("cannot start subtransactions during a parallel operation"). A wider
-- report window (e.g. 7 days against 1) gives the planner enough rows to prefer a
-- parallel plan, and declaring this function SAFE only defers that failure from compile
-- time to whichever query first crosses the row-count threshold -- exactly what
-- happened here. Marking it UNSAFE tells the planner to run any query that calls it
-- without parallel workers instead.
CREATE OR REPLACE FUNCTION safe_jsonb(input text) RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
PARALLEL UNSAFE
AS $$
BEGIN
    IF input IS NULL OR input = '' THEN
        RETURN NULL;
    END IF;
    RETURN input::jsonb;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;
