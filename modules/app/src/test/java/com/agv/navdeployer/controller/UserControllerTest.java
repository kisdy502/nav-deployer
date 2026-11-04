package com.agv.navdeployer.controller;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.service.UserService;
import com.agv.navdeployer.vo.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    /** 验证创建用户成功时返回 201，响应体为 status/msg/data 信封结构，且字段序列化为 snake_case（created_at、avatar_key） */
    @Test
    void create_should_return_201_with_envelope_and_snake_case() throws Exception {
        when(userService.create(any())).thenReturn(vo(1L, "zhangsan", 25));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zhangsan\",\"age\":25}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("zhangsan"))
                .andExpect(jsonPath("$.data.created_at").exists())
                .andExpect(jsonPath("$.data.avatar_key").value("avatars/demo.png"));
    }

    /** 验证 age 超出取值范围（200）时返回 400，且信封中包含 msg 错误信息 */
    @Test
    void create_should_return_400_with_message_when_age_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zhangsan\",\"age\":200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.msg").exists());
    }

    /** 验证 name 为空字符串时校验失败，创建用户返回 400 */
    @Test
    void create_should_return_400_when_name_blank() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"age\":25}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /** 验证按 ID 查询存在用户时返回 200，且用户数据位于 data 字段中 */
    @Test
    void getById_should_return_data() throws Exception {
        when(userService.getById(1L)).thenReturn(vo(1L, "zhangsan", 25));

        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.name").value("zhangsan"));
    }

    /** 验证查询不存在用户抛出 NotFoundException 时返回 404 信封，msg 携带错误信息且无 data 字段 */
    @Test
    void getById_should_return_404_envelope_when_missing() throws Exception {
        when(userService.getById(9L)).thenThrow(new NotFoundException("用户 9 不存在"));

        mockMvc.perform(get("/api/v1/users/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.msg").value("用户 9 不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 验证列表查询返回 200 且 data 为数组，支持 name 参数过滤 */
    @Test
    void list_should_return_data_array() throws Exception {
        when(userService.list(any())).thenReturn(List.of(vo(1L, "zhangsan", 25), vo(2L, "lisi", 30)));

        mockMvc.perform(get("/api/v1/users").param("name", "zhang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("zhangsan"));
    }

    /** 验证更新请求缺少 age 字段时请求体校验失败，返回 400 */
    @Test
    void update_should_return_400_when_body_invalid() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"lisi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /** 验证删除用户成功时返回 200 成功信封，且不携带 data 字段 */
    @Test
    void delete_should_return_success_envelope() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private UserVO vo(Long id, String name, Integer age) {
        UserVO vo = new UserVO();
        vo.setId(id);
        vo.setName(name);
        vo.setAge(age);
        vo.setAvatarKey("avatars/demo.png");
        vo.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        vo.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        return vo;
    }
}
