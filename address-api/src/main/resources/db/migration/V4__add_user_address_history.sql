CREATE TABLE user_address_history (
    id                       BIGSERIAL    PRIMARY KEY,
    address_id               BIGINT,
    user_id                  BIGINT       NOT NULL,
    action_type              VARCHAR(10)  NOT NULL,
    before_recipient_name    VARCHAR(100),
    before_recipient_address VARCHAR(255),
    before_is_default        BOOLEAN,
    after_recipient_name     VARCHAR(100),
    after_recipient_address  VARCHAR(255),
    after_is_default         BOOLEAN,
    created_at               TIMESTAMP    NOT NULL DEFAULT now()
);
