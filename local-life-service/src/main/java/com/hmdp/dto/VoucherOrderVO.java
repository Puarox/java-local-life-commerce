package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class VoucherOrderVO {
    // JavaScript cannot represent generated 64-bit order IDs exactly.
    private String id;
    private Long voucherId;
    private Long shopId;
    private String shopName;
    private String title;
    private Long payValue;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}
