-- History is paged per user by message id (latest 50, then older pages before a cursor).
CREATE INDEX idx_chat_messages_user_id ON chat_messages(user_id, id);
