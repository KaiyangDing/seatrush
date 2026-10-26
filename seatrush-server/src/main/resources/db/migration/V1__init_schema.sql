CREATE TABLE users (
                       id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                       username      VARCHAR(50)  NOT NULL,
                       password_hash VARCHAR(100) NOT NULL,
                       role          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
                       created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                       CONSTRAINT uk_users_username UNIQUE (username),
                       CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE TABLE events (
                        id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        name          VARCHAR(200) NOT NULL,
                        venue         VARCHAR(200) NOT NULL,
                        starts_at     DATETIME(3)  NOT NULL,
                        seat_rows     INT          NOT NULL,
                        seats_per_row INT          NOT NULL,
                        created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE holds (
                       id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                       user_id         BIGINT      NOT NULL,
                       event_id        BIGINT      NOT NULL,
                       status          VARCHAR(10) NOT NULL,
                       idempotency_key VARCHAR(64) NOT NULL,
                       expires_at      DATETIME(3) NOT NULL,
                       created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                       confirmed_at    DATETIME(3) NULL,
                       CONSTRAINT fk_holds_user   FOREIGN KEY (user_id)  REFERENCES users (id),
                       CONSTRAINT fk_holds_event  FOREIGN KEY (event_id) REFERENCES events (id),
                       CONSTRAINT uk_holds_idem   UNIQUE (user_id, idempotency_key),
                       CONSTRAINT ck_holds_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
                       INDEX idx_holds_status_expires (status, expires_at)
);

CREATE TABLE seats (
                       id       BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                       event_id BIGINT      NOT NULL,
                       row_no   INT         NOT NULL,
                       seat_no  INT         NOT NULL,
                       status   VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
                       hold_id  BIGINT      NULL,
                       version  BIGINT      NOT NULL DEFAULT 0,
                       CONSTRAINT fk_seats_event    FOREIGN KEY (event_id) REFERENCES events (id),
                       CONSTRAINT fk_seats_hold     FOREIGN KEY (hold_id)  REFERENCES holds (id),
                       CONSTRAINT uk_seats_position UNIQUE (event_id, row_no, seat_no),
                       CONSTRAINT ck_seats_status   CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
                       INDEX idx_seats_event_status (event_id, status)
);