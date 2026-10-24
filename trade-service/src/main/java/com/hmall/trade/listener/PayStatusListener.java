package com.hmall.trade.listener;

import com.hmall.trade.service.IOrderService;
import com.hmall.trade.config.PayQueueTopology;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PayStatusListener {

    @Autowired
    private  IOrderService orderService; ;



    @RabbitListener(queues = PayQueueTopology.PAY_QUEUE)
    public void listenPaySuccess(Long orderId){
        orderService.markOrderPaySuccess(orderId);
    }
}
