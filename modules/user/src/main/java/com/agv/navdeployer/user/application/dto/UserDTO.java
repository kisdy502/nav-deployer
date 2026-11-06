package com.agv.navdeployer.user.application.dto;

import java.time.LocalDateTime;

public record UserDTO(Long id,
                      String name,
                      Integer age,
                      String avatarKey,
                      LocalDateTime createdAt,
                      LocalDateTime updatedAt) {
}
