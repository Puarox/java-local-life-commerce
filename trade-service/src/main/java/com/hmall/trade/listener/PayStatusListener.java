package com.hmall.trade.listener;

import com.hmall.trade.service.IOrderService;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PayStatusListener {

    @Autowired
    private  IOrderService orderService; ;



    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name="trade.pay.success.queue",durable = "true"),
            exchange = @Exchange(name="pay.direct"),
            key = "pay.success"
    ))
    public void listenPaySuccess(Long orderId){
        orderService.markOrderPaySuccess(orderId);
    }
}
