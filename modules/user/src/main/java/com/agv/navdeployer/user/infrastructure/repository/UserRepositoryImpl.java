package com.agv.navdeployer.user.infrastructure.repository;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.user.domain.User;
import com.agv.navdeployer.user.domain.UserErrorCode;
import com.agv.navdeployer.user.domain.repository.UserRepository;
import com.agv.navdeployer.user.infrastructure.repository.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserMapper userMapper;

    public UserRepositoryImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void insert(User user) {
        userMapper.insert(user);
    }

    @Override
    public void update(User user) {
        userMapper.updateById(user);
    }

    @Override
    public void deleteById(Long id) {
        userMapper.deleteById(id);
    }

    @Override
    public User findById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND, "用户 " + id + " 不存在");
        }
        return user;
    }

    @Override
    public List<User> findByName(String name) {
        return userMapper.selectList(Wrappers.lambdaQuery(User.class)
                .like(StringUtils.hasText(name), User::getName, name)
                .orderByDesc(User::getId));
    }
}
