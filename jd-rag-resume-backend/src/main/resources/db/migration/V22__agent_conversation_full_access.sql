-- 完全访问: read-only browser tasks listed in FullAccessPolicy start without the user's click. Off by default.
ALTER TABLE agent_conversation
    ADD COLUMN full_access BOOLEAN NOT NULL DEFAULT FALSE;
