package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class VoucherActivityVO {
    private Long id;
    private Long shopId;
    private String shopName;
    private String shopImages;
    private String title;
    private String subTitle;
    private Long payValue;
    private Long actualValue;
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
}
