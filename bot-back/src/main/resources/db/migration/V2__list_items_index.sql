-- list_items was first created by Hibernate without an index; lists are always read per user.
CREATE INDEX IF NOT EXISTS idx_list_items_user_list ON list_items(user_id, list_name);
