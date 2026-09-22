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

    @Test
    void create_should_return_400_with_message_when_age_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zhangsan\",\"age\":200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.msg").exists());
    }

    @Test
    void create_should_return_400_when_name_blank() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"age\":25}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getById_should_return_data() throws Exception {
        when(userService.getById(1L)).thenReturn(vo(1L, "zhangsan", 25));

        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.name").value("zhangsan"));
    }

    @Test
    void getById_should_return_404_envelope_when_missing() throws Exception {
        when(userService.getById(9L)).thenThrow(new NotFoundException("用户 9 不存在"));

        mockMvc.perform(get("/api/v1/users/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.msg").value("用户 9 不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void list_should_return_data_array() throws Exception {
        when(userService.list(any())).thenReturn(List.of(vo(1L, "zhangsan", 25), vo(2L, "lisi", 30)));

        mockMvc.perform(get("/api/v1/users").param("name", "zhang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("zhangsan"));
    }

    @Test
    void update_should_return_400_when_body_invalid() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"lisi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

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
