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

    /** 验证创建用户：插入数据库后回填自增 id，并返回含完整字段的 VO。 */
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

    /** 验证按 id 查询存在的用户时正确映射为 VO（含头像 key）。 */
    @Test
    void getById_should_return_vo_when_user_exists() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        UserVO vo = userService.getById(1L);

        assertEquals("zhangsan", vo.getName());
        assertEquals("avatars/a.png", vo.getAvatarKey());
    }

    /** 验证查询不存在的用户时抛出 NotFoundException 且消息包含该 id。 */
    @Test
    void getById_should_throw_when_user_missing() {
        when(userMapper.selectById(9L)).thenReturn(null);

        NotFoundException ex = assertThrows(NotFoundException.class, () -> userService.getById(9L));

        assertTrue(ex.getMessage().contains("9"));
    }

    /** 验证列表查询：按名称条件过滤并返回映射后的 VO 列表。 */
    @Test
    void list_should_filter_by_name_and_order_desc() {
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(user(1L, "zhangsan", 25, null)));

        java.util.List<UserVO> result = userService.list(new UserQuery("zhang"));

        assertEquals(1, result.size());
        assertEquals("zhangsan", result.get(0).getName());
        verify(userMapper).selectList(any());
    }

    /** 验证更新用户：用传入字段覆盖并持久化，返回更新后的 VO。 */
    @Test
    void update_should_update_fields() {
        User existing = user(1L, "old", 20, null);
        when(userMapper.selectById(1L)).thenReturn(existing);

        UserVO vo = userService.update(1L, new UserUpdateDTO("new", 30));

        assertEquals("new", vo.getName());
        assertEquals(30, vo.getAge());
        verify(userMapper).updateById(existing);
    }

    /** 验证更新不存在的用户时抛出 NotFoundException。 */
    @Test
    void update_should_throw_when_user_missing() {
        when(userMapper.selectById(9L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> userService.update(9L, new UserUpdateDTO("new", 30)));
    }

    /** 验证删除用户时同时删除数据库记录和 MinIO 中的头像对象。 */
    @Test
    void delete_should_remove_row_and_avatar_object() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        userService.delete(1L);

        verify(userMapper).deleteById(1L);
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    /** 验证用户无头像时删除只清数据库记录，不调用 MinIO 删除。 */
    @Test
    void delete_should_skip_minio_when_no_avatar() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, null));

        userService.delete(1L);

        verify(userMapper).deleteById(1L);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    /** 验证上传空文件被拒绝且不更新用户数据。 */
    @Test
    void uploadAvatar_should_reject_empty_file() {
        MultipartFile empty = new MockMultipartFile("file", new byte[0]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.uploadAvatar(1L, empty));

        assertTrue(ex.getMessage().contains("不能为空"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 验证上传非图片扩展名（如 .txt）的头像被拒绝，防止存入非法文件。 */
    @Test
    void uploadAvatar_should_reject_unsupported_extension() {
        MultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> userService.uploadAvatar(1L, file));
        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 验证上传头像：对象写入 MinIO 并将新 key 保存到用户记录（替换旧头像 key）。 */
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

    /** 验证为不存在的用户上传头像时抛出 NotFoundException。 */
    @Test
    void uploadAvatar_should_throw_when_user_missing() throws Exception {
        stubTransactionExecute();
        when(userMapper.selectById(9L)).thenReturn(null);
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        assertThrows(NotFoundException.class, () -> userService.uploadAvatar(9L, file));
    }

    /** 验证用户未设置头像时获取头像地址抛出 NotFoundException。 */
    @Test
    void getAvatarUrl_should_throw_when_no_avatar() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, null));

        assertThrows(NotFoundException.class, () -> userService.getAvatarUrl(1L, "http://localhost:9000"));
    }

    /** 验证签名下载 URL 按调用方指定的 endpoint 生成，且带有效期与签名参数。 */
    @Test
    void getAvatarUrl_should_return_url_signed_for_requested_endpoint() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "zhangsan", 25, "avatars/a.png"));

        AvatarUrlVO vo = userService.getAvatarUrl(1L, "http://192.168.1.100:9000");

        assertEquals(86400, vo.expireInSeconds());
        assertTrue(vo.url().startsWith("http://192.168.1.100:9000/user-avatars/avatars/a.png?"));
        assertTrue(vo.url().contains("X-Amz-Signature"));
    }

    /** 验证 MinIO 取对象失败时下载头像抛出 IllegalStateException，不向外泄漏底层异常。 */
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
