package com.hmall.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserProfileDTO implements Serializable {
    private Long id;
    private String username;
    private String nickName;
    private String icon;
}
