"""SQL schemas. Personal and Enterprise live in physically separate database
files so no query can ever join across the two realms."""

_AUDIT = """
CREATE TABLE audit_log(
  scope_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL,
  actor_id TEXT, actor_role TEXT, action TEXT NOT NULL,
  target_type TEXT, target_id TEXT, ip TEXT, meta TEXT NOT NULL DEFAULT '{}',
  prev_hash TEXT NOT NULL, hash TEXT NOT NULL,
  PRIMARY KEY(scope_id, seq)
);
CREATE TABLE audit_checkpoints(scope_id TEXT PRIMARY KEY, up_to_seq INTEGER NOT NULL, hash TEXT NOT NULL, ts INTEGER NOT NULL)
"""

PERSONAL = [
    """
CREATE TABLE users(
  id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, pw_hash TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active',
  failed_logins INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, last_login_at INTEGER
);
CREATE TABLE user_keys(user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, wrapped TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE sessions(
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash TEXT NOT NULL, prev_refresh_hash TEXT, label TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE TABLE privacy(
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  cloud_processing INTEGER NOT NULL DEFAULT 0, memory_sync INTEGER NOT NULL DEFAULT 0,
  retention_days INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE memory_entries(
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, id TEXT NOT NULL,
  type TEXT NOT NULL, title_enc TEXT NOT NULL, content_enc TEXT NOT NULL,
  updated_at INTEGER NOT NULL, deleted_at INTEGER, PRIMARY KEY(user_id, id)
);
CREATE INDEX memory_user_updated ON memory_entries(user_id, updated_at);
CREATE TABLE reminders(
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, id TEXT NOT NULL,
  title_enc TEXT NOT NULL, notes_enc TEXT NOT NULL, due_at INTEGER NOT NULL,
  repeat TEXT NOT NULL DEFAULT 'none', done_at INTEGER, updated_at INTEGER NOT NULL,
  deleted_at INTEGER, PRIMARY KEY(user_id, id)
);
CREATE INDEX reminders_user_due ON reminders(user_id, due_at);
CREATE TABLE devices(
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  hw_id TEXT NOT NULL, label TEXT NOT NULL, secret_wrapped TEXT NOT NULL,
  fw_version TEXT, battery_pct INTEGER, last_seen_at INTEGER,
  created_at INTEGER NOT NULL, revoked_at INTEGER, UNIQUE(user_id, hw_id)
);
CREATE TABLE notifications(
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL,
  read_at INTEGER, ref_type TEXT, ref_id TEXT
);
CREATE INDEX notifications_user ON notifications(user_id, created_at);
CREATE TABLE ai_activity(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ts INTEGER NOT NULL, kind TEXT NOT NULL, bytes_in INTEGER NOT NULL, bytes_out INTEGER NOT NULL, model TEXT
);
CREATE INDEX ai_activity_user_ts ON ai_activity(user_id, ts);
""" + _AUDIT,
]

ENTERPRISE = [
    """
CREATE TABLE orgs(
  id TEXT PRIMARY KEY, slug TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active', ai_monthly_quota INTEGER NOT NULL,
  policy TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL
);
CREATE TABLE tenant_keys(org_id TEXT PRIMARY KEY REFERENCES orgs(id) ON DELETE CASCADE, wrapped TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE divisions(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(org_id, name)
);
CREATE TABLE employees(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  email TEXT NOT NULL, display_name TEXT NOT NULL, title TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL CHECK(role IN ('owner','admin','manager','member','auditor')),
  division_id TEXT REFERENCES divisions(id) ON DELETE SET NULL, pw_hash TEXT,
  status TEXT NOT NULL DEFAULT 'invited' CHECK(status IN ('invited','active','disabled')),
  failed_logins INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, last_login_at INTEGER, UNIQUE(org_id, email)
);
CREATE TABLE invites(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  employee_id TEXT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE, expires_at INTEGER NOT NULL, used_at INTEGER,
  created_by TEXT, created_at INTEGER NOT NULL
);
CREATE TABLE sessions(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  employee_id TEXT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  refresh_hash TEXT NOT NULL, prev_refresh_hash TEXT, label TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER
);
CREATE INDEX esessions_emp ON sessions(employee_id);
CREATE TABLE projects(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name_enc TEXT NOT NULL, description_enc TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','paused','done','archived')),
  owner_id TEXT REFERENCES employees(id) ON DELETE SET NULL,
  division_id TEXT REFERENCES divisions(id) ON DELETE SET NULL,
  due_date TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE tasks(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
  title_enc TEXT NOT NULL, description_enc TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'todo' CHECK(status IN ('todo','doing','blocked','done')),
  priority TEXT NOT NULL DEFAULT 'normal' CHECK(priority IN ('low','normal','high','urgent')),
  assignee_id TEXT REFERENCES employees(id) ON DELETE SET NULL,
  creator_id TEXT REFERENCES employees(id) ON DELETE SET NULL,
  division_id TEXT REFERENCES divisions(id) ON DELETE SET NULL,
  due_date TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER
);
CREATE INDEX tasks_org_status ON tasks(org_id, status);
CREATE INDEX tasks_org_assignee ON tasks(org_id, assignee_id);
CREATE TABLE task_comments(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  author_id TEXT REFERENCES employees(id) ON DELETE SET NULL, body_enc TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE announcements(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  title_enc TEXT NOT NULL, body_enc TEXT NOT NULL, author_id TEXT REFERENCES employees(id) ON DELETE SET NULL,
  division_id TEXT REFERENCES divisions(id) ON DELETE CASCADE, pinned INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, expires_at INTEGER
);
CREATE TABLE kb_documents(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  title_enc TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
  visibility TEXT NOT NULL DEFAULT 'org' CHECK(visibility IN ('org','division','managers','admins')),
  division_id TEXT REFERENCES divisions(id) ON DELETE CASCADE,
  created_by TEXT REFERENCES employees(id) ON DELETE SET NULL, created_at INTEGER NOT NULL,
  chunk_count INTEGER NOT NULL, content_hash TEXT NOT NULL, UNIQUE(org_id, content_hash)
);
CREATE TABLE kb_chunks(
  id INTEGER PRIMARY KEY AUTOINCREMENT, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  doc_id TEXT NOT NULL REFERENCES kb_documents(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL, text_enc TEXT NOT NULL, token_count INTEGER NOT NULL
);
CREATE INDEX kb_chunks_doc ON kb_chunks(doc_id);
CREATE TABLE kb_terms(
  org_id TEXT NOT NULL, term_hash INTEGER NOT NULL,
  chunk_id INTEGER NOT NULL REFERENCES kb_chunks(id) ON DELETE CASCADE, tf INTEGER NOT NULL,
  PRIMARY KEY(org_id, term_hash, chunk_id)
);
CREATE INDEX kb_terms_chunk ON kb_terms(chunk_id);
CREATE TABLE meetings(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  title_enc TEXT NOT NULL, held_at INTEGER NOT NULL, notes_enc TEXT NOT NULL,
  summary_enc TEXT, actions_enc TEXT, summary_status TEXT NOT NULL DEFAULT 'none',
  division_id TEXT REFERENCES divisions(id) ON DELETE SET NULL,
  created_by TEXT REFERENCES employees(id) ON DELETE SET NULL, created_at INTEGER NOT NULL
);
CREATE TABLE reports(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  kind TEXT NOT NULL, params TEXT NOT NULL, metrics TEXT NOT NULL, narrative_enc TEXT,
  created_by TEXT REFERENCES employees(id) ON DELETE SET NULL, created_at INTEGER NOT NULL
);
CREATE TABLE devices(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  owner_id TEXT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  hw_id TEXT NOT NULL, label TEXT NOT NULL, secret_wrapped TEXT NOT NULL,
  fw_version TEXT, battery_pct INTEGER, last_seen_at INTEGER,
  created_at INTEGER NOT NULL, revoked_at INTEGER, UNIQUE(org_id, hw_id)
);
CREATE TABLE notifications(
  id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  employee_id TEXT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL,
  read_at INTEGER, ref_type TEXT, ref_id TEXT
);
CREATE INDEX enotifications_emp ON notifications(employee_id, created_at);
CREATE TABLE ai_usage(
  org_id TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE, day TEXT NOT NULL,
  division_id TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL,
  requests INTEGER NOT NULL DEFAULT 0, tokens_in INTEGER NOT NULL DEFAULT 0, tokens_out INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(org_id, day, division_id, kind)
);
""" + _AUDIT,
]
