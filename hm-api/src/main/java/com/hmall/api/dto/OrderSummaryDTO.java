package com.hmall.api.dto;

import lombok.Data;

@Data
public class OrderSummaryDTO {
    private Long id;
    private Long userId;
    private Integer totalFee;
    private Integer status;
}
