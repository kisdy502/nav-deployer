package com.agv.navdeployer.user.domain.repository;

import com.agv.navdeployer.user.domain.User;

import java.util.List;

public interface UserRepository {

    void insert(User user);

    void update(User user);

    void deleteById(Long id);

    User findById(Long id);

    /**
     * @param name 模糊匹配关键字，null/空白返回全部（id 倒序）
     */
    List<User> findByName(String name);
}
