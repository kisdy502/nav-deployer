package com.agv.navdeployer.user.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("users")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer age;

    private String avatarKey;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public User(String name, Integer age) {
        this.name = name;
        this.age = age;
    }

    public void updateProfile(String name, Integer age) {
        this.name = name;
        this.age = age;
        this.updatedAt = null;
    }

    /**
     * @return 被替换的旧头像对象 key（无旧头像返回 null，供上层清理）
     */
    public String changeAvatar(String objectKey) {
        String oldKey = this.avatarKey;
        this.avatarKey = objectKey;
        this.updatedAt = null;
        return oldKey;
    }

    public boolean checkHasAvatar() {
        return avatarKey != null;
    }
}
