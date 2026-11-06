package com.agv.navdeployer.user.application;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.user.application.cmd.UserCreateCmd;
import com.agv.navdeployer.user.application.cmd.UserUpdateCmd;
import com.agv.navdeployer.user.application.dto.AvatarUrlDTO;
import com.agv.navdeployer.user.application.dto.UserDTO;
import com.agv.navdeployer.user.application.query.UserQuery;
import com.agv.navdeployer.user.domain.User;
import com.agv.navdeployer.user.domain.UserErrorCode;
import com.agv.navdeployer.user.domain.repository.UserRepository;
import com.agv.navdeployer.user.infrastructure.avatar.UserAvatarStore;
import com.agv.navdeployer.user.infrastructure.mapstruct.UserMapstruct;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

@Service
public class UserService {

    private static final Duration AVATAR_URL_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final UserMapstruct userMapstruct;
    private final UserAvatarStore avatarStore;
    private final TransactionTemplate transactionTemplate;

    public UserService(UserRepository userRepository,
                       UserMapstruct userMapstruct,
                       UserAvatarStore avatarStore,
                       TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.userMapstruct = userMapstruct;
        this.avatarStore = avatarStore;
        this.transactionTemplate = transactionTemplate;
    }

    @CachePut(value = "users", key = "#result.id")
    @Transactional(rollbackFor = Exception.class)
    public UserDTO create(UserCreateCmd cmd) {
        User user = new User(cmd.name(), cmd.age());
        userRepository.insert(user);
        return userMapstruct.toDTO(user);
    }

    @Cacheable(value = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserDTO getById(Long id) {
        return userMapstruct.toDTO(userRepository.findById(id));
    }

    @Transactional(readOnly = true)
    public List<UserDTO> list(UserQuery query) {
        return userMapstruct.toDTOList(userRepository.findByName(query.name()));
    }

    @CachePut(value = "users", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public UserDTO update(Long id, UserUpdateCmd cmd) {
        User user = userRepository.findById(id);
        user.updateProfile(cmd.name(), cmd.age());
        userRepository.update(user);
        return userMapstruct.toDTO(user);
    }

    @CacheEvict(value = "users", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = userRepository.findById(id);
        userRepository.deleteById(user.getId());
        afterCommitOrNow(() -> avatarStore.removeQuietly(user.getAvatarKey()));
    }

    @CachePut(value = "users", key = "#id")
    public UserDTO uploadAvatar(Long id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(UserErrorCode.AVATAR_FILE_EMPTY);
        }
        String objectKey = avatarStore.put(file);
        User updated = transactionTemplate.execute(status -> {
            User user = userRepository.findById(id);
            String oldKey = user.changeAvatar(objectKey);
            userRepository.update(user);
            afterCommitOrNow(() -> avatarStore.removeQuietly(oldKey));
            return user;
        });
        return userMapstruct.toDTO(updated);
    }

    @Transactional(readOnly = true)
    public UserAvatarStore.AvatarObject getAvatar(Long id) {
        User user = userRepository.findById(id);
        if (!user.checkHasAvatar()) {
            throw new BusinessException(UserErrorCode.AVATAR_NOT_SET, "用户 " + id + " 尚未设置头像");
        }
        return avatarStore.get(user.getAvatarKey());
    }

    @Transactional(readOnly = true)
    public AvatarUrlDTO getAvatarUrl(Long id, String externalEndpoint) {
        User user = userRepository.findById(id);
        if (!user.checkHasAvatar()) {
            throw new BusinessException(UserErrorCode.AVATAR_NOT_SET, "用户 " + id + " 尚未设置头像");
        }
        String url = avatarStore.presignGetUrl(user.getAvatarKey(), externalEndpoint, AVATAR_URL_TTL.toSeconds());
        return new AvatarUrlDTO(url, AVATAR_URL_TTL.toSeconds());
    }

    private void afterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
