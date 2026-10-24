package com.hmall.pay.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class PayOutboxRepository {
    private final JdbcTemplate jdbc;

    public void record(Long payOrderId, Long bizOrderNo, String routingKey) {
        jdbc.update("INSERT INTO pay_outbox(pay_order_id,biz_order_no,routing_key) VALUES(?,?,?)",
                payOrderId, bizOrderNo, routingKey);
    }

    public List<Event> due() {
        return jdbc.query("SELECT id,biz_order_no,routing_key,attempts FROM pay_outbox " +
                        "WHERE state<>2 AND next_retry_at<=NOW(6) ORDER BY id LIMIT 20",
                (rs, row) -> new Event(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)));
    }

    public boolean lease(Long id) {
        return jdbc.update("UPDATE pay_outbox SET state=1,attempts=attempts+1," +
                "next_retry_at=DATE_ADD(NOW(6), INTERVAL 30 SECOND) " +
                "WHERE id=? AND state<>2 AND next_retry_at<=NOW(6)", id) == 1;
    }

    public void sent(Long id) {
        jdbc.update("UPDATE pay_outbox SET state=2,published_at=NOW(6),last_error=NULL WHERE id=? AND state=1", id);
    }

    public void retry(Long id, int delaySeconds, String error) {
        jdbc.update("UPDATE pay_outbox SET state=0,next_retry_at=DATE_ADD(NOW(6), INTERVAL ? SECOND)," +
                "last_error=? WHERE id=? AND state=1", delaySeconds, error, id);
    }

    // Repair payment rows created before the outbox migration or interrupted before dispatch.
    public int reconcileMissing() {
        return jdbc.update("INSERT IGNORE INTO pay_outbox(pay_order_id,biz_order_no,routing_key) " +
                "SELECT id,biz_order_no,CASE WHEN expand_json='{\"source\":\"voucher\"}' " +
                "THEN 'voucher.pay.success' ELSE 'pay.success' END " +
                "FROM pay_order WHERE status=3 AND is_delete=0");
    }

    public List<Reconciliation> paidAfter(long cursor) {
        return jdbc.query("SELECT o.id,p.biz_order_no,p.biz_user_id,p.expand_json " +
                        "FROM pay_outbox o JOIN pay_order p ON p.id=o.pay_order_id " +
                        "WHERE o.id>? AND o.state=2 AND p.status=3 " +
                        "AND o.published_at<DATE_SUB(NOW(6), INTERVAL 30 SECOND) " +
                        "ORDER BY o.id LIMIT 20", (rs, row) -> new Reconciliation(
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)), cursor);
    }

    public void requeue(Long id) {
        jdbc.update("UPDATE pay_outbox SET state=0,next_retry_at=NOW(6) WHERE id=? AND state=2", id);
    }

    public static class Reconciliation {
        public final Long id;
        public final Long bizOrderNo;
        public final Long userId;
        public final String source;
        public Reconciliation(Long id, Long bizOrderNo, Long userId, String source) {
            this.id = id; this.bizOrderNo = bizOrderNo; this.userId = userId; this.source = source;
        }
    }

    public static class Event {
        public final Long id;
        public final Long bizOrderNo;
        public final String routingKey;
        public final int attempts;

        public Event(Long id, Long bizOrderNo, String routingKey, int attempts) {
            this.id = id;
            this.bizOrderNo = bizOrderNo;
            this.routingKey = routingKey;
            this.attempts = attempts;
        }
    }
}
