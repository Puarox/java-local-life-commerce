CREATE TABLE IF NOT EXISTS pay_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  pay_order_id BIGINT NOT NULL,
  biz_order_no BIGINT NOT NULL,
  routing_key VARCHAR(64) NOT NULL,
  state TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 leased, 2 broker confirmed',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at DATETIME(6) NULL,
  last_error VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_order (pay_order_id),
  KEY idx_due (state, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
