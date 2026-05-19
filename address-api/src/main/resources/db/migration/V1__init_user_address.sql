CREATE TABLE user_address (
    id                BIGINT       PRIMARY KEY,
    recipient_name    VARCHAR(100) NOT NULL,
    recipient_address VARCHAR(255) NOT NULL
);

INSERT INTO user_address (id, recipient_name, recipient_address) VALUES
(1, '홍길동', '서울시 강남구 테헤란로 1'),
(2, '김철수', '부산시 해운대구 달맞이길 2'),
(3, '이영희', '대구시 중구 동성로 3');
