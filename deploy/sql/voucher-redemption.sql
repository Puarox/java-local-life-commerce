ALTER TABLE tb_voucher_order ADD COLUMN redeem_code VARCHAR(12) NULL AFTER refund_time;
CREATE UNIQUE INDEX uk_redeem_code ON tb_voucher_order (redeem_code);
