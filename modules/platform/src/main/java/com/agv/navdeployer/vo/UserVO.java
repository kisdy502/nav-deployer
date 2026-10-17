package com.agv.navdeployer.vo;

import com.agv.navdeployer.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;

    private String name;

    private Integer age;

    private String avatarKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static UserVO from(User user) {
        return new UserVO(user.getId(), user.getName(), user.getAge(),
                user.getAvatarKey(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
