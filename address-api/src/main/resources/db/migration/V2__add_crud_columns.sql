ALTER TABLE user_address ADD COLUMN user_id    BIGINT  NOT NULL DEFAULT 0;
ALTER TABLE user_address ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE user_address ADD COLUMN deleted    BOOLEAN NOT NULL DEFAULT false;

UPDATE user_address SET user_id = 1 WHERE id IN (1, 2, 3);
UPDATE user_address SET is_default = true WHERE id = 1;

ALTER TABLE user_address ALTER COLUMN user_id DROP DEFAULT;

CREATE SEQUENCE user_address_id_seq START WITH 100;
ALTER TABLE user_address ALTER COLUMN id SET DEFAULT nextval('user_address_id_seq');
ALTER SEQUENCE user_address_id_seq OWNED BY user_address.id;
