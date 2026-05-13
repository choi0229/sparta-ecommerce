-- 사용자별 기본 배송지는 최대 1개 (soft-delete된 행은 제외)
CREATE UNIQUE INDEX ux_user_address_default_active
    ON user_address (user_id)
    WHERE is_default = true AND deleted = false;
