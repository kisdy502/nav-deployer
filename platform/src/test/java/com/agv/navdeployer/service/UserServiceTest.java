package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.UserCreateDTO;
import com.agv.navdeployer.dto.UserQuery;
import com.agv.navdeployer.dto.UserUpdateDTO;
import com.agv.navdeployer.entity.User;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.UserMapper;
import com.agv.navdeployer.vo.AvatarUrlVO;
import com.agv.navdeployer.vo.UserVO;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private MinioClient minioClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, minioClient, transactionTemplate,
                "user-avatars", "minioadmin", "minioadmin123", "us-east-1");
    }

    @Test
    void create_should_insert_and_return_vo() {
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            inv.getArgument(0, User.class).setId(1L);
            return 1;
        });

        UserVO vo = userService.create(new UserCreateDTO("zhangsan", 25));

        assertEquals(1L, vo.getId());
        assertEquals("zhangsan", vo.getName());
        assertEquals(25, vo.getAge());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void getById_should_return_vo_when_user_exists() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        UserVO vo = userService.getById(1L);

        assertEquals("zhangsan", vo.getName());
        assertEquals("avatars/a.png", vo.getAvatarKey());
    }

    @Test
    void getById_should_throw_when_user_missing() {
        when(userMapper.selectById(9L)).thenReturn(null);

        NotFoundException ex = assertThrows(NotFoundException.class, () -> userService.getById(9L));

        assertTrue(ex.getMessage().contains("9"));
    }

    @Test
    void list_should_filter_by_name_and_order_desc() {
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(user(1L, "zhangsan", 25, null)));

        java.util.List<UserVO> result = userService.list(new UserQuery("zhang"));

        assertEquals(1, result.size());
        assertEquals("zhangsan", result.get(0).getName());
        verify(userMapper).selectList(any());
    }

    @Test
    void update_should_update_fields() {
        User existing = user(1L, "old", 20, null);
        when(userMapper.selectById(1L)).thenReturn(existing);

        UserVO vo = userService.update(1L, new UserUpdateDTO("new", 30));

        assertEquals("new", vo.getName());
        assertEquals(30, vo.getAge());
        verify(userMapper).updateById(existing);
    }

    @Test
    void update_should_throw_when_user_missing() {
        when(userMapper.selectById(9L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> userService.update(9L, new UserUpdateDTO("new", 30)));
    }

    @Test
    void delete_should_remove_row_and_avatar_object() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        userService.delete(1L);

        verify(userMapper).deleteById(1L);
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void delete_should_skip_minio_when_no_avatar() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, null));

        userService.delete(1L);

        verify(userMapper).deleteById(1L);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void uploadAvatar_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", new byte[0]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.uploadAvatar(1L, empty));

        assertTrue(ex.getMessage().contains("不能为空"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void uploadAvatar_should_reject_unsupported_extension() {
        MultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> userService.uploadAvatar(1L, file));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void uploadAvatar_should_upload_object_and_save_key() throws Exception {
        stubTransactionExecute();
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/old.png"));
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        UserVO vo = userService.uploadAvatar(1L, file);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(userMapper).updateById(any(User.class));
        assertNotNull(vo.getAvatarKey());
        assertTrue(vo.getAvatarKey().startsWith("avatars/"));
        assertTrue(vo.getAvatarKey().endsWith(".png"));
    }

    @Test
    void uploadAvatar_should_throw_when_user_missing() throws Exception {
        stubTransactionExecute();
        when(userMapper.selectById(9L)).thenReturn(null);
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        assertThrows(NotFoundException.class, () -> userService.uploadAvatar(9L, file));
    }

    @Test
    void getAvatarUrl_should_throw_when_no_avatar() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, null));

        assertThrows(NotFoundException.class, () -> userService.getAvatarUrl(1L, "http://localhost:9000"));
    }

    @Test
    void getAvatarUrl_should_return_url_signed_for_requested_endpoint() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        AvatarUrlVO vo = userService.getAvatarUrl(1L, "http://192.168.1.100:9000");

        assertEquals(86400, vo.expireInSeconds());
        assertTrue(vo.url().startsWith("http://192.168.1.100:9000/user-avatars/avatars/a.png?"));
        assertTrue(vo.url().contains("X-Amz-Signature"));
    }

    @Test
    void getAvatar_should_throw_when_minio_fails() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new RuntimeException("boom"));

        assertThrows(IllegalStateException.class, () -> userService.getAvatar(1L));
    }

    private void stubTransactionExecute() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private User user(Long id, String name, Integer age, String avatarKey) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setAge(age);
        user.setAvatarKey(avatarKey);
        return user;
    }
}
