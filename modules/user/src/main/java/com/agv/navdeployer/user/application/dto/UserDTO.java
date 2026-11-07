package com.agv.navdeployer.user.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private Long id;

    private String name;

    private Integer age;

    private String avatarKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
