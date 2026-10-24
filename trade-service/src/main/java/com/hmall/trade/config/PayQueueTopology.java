package com.hmall.trade.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PayQueueTopology {
    public static final String PAY_QUEUE = "trade.pay.success.queue.v2";
    public static final String DEAD_LETTER_QUEUE = "trade.pay.success.dlq";

    @Bean
    public Declarables tradePayStatusTopology() {
        DirectExchange payExchange = new DirectExchange("pay.direct", true, false);
        DirectExchange deadLetterExchange = new DirectExchange("pay.dlx", true, false);
        Queue paymentQueue = QueueBuilder.durable(PAY_QUEUE)
                .deadLetterExchange("pay.dlx")
                .deadLetterRoutingKey("trade.pay.success.failed")
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(payExchange, deadLetterExchange, paymentQueue, deadLetterQueue,
                BindingBuilder.bind(paymentQueue).to(payExchange).with("pay.success"),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("trade.pay.success.failed"));
    }
}
