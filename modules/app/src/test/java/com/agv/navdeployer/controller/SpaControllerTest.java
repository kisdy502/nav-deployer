package com.agv.navdeployer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();

    /** 验证 /editor/{id}、/map2/{id} 等前端深链接被转发到 index.html，由前端路由接管 */
    @Test
    void editorDeepLinksForwardToFrontend() throws Exception {
        for (String path : new String[]{"/editor/1", "/map2/1"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    /** 验证不存在的 /api 与 /assets 路径不会被转发到前端，而是直接返回 404，避免掩盖后端资源缺失 */
    @Test
    void missingApiAndAssetsDoNotForwardToFrontend() throws Exception {
        for (String path : new String[]{"/api/v2/missing", "/assets/missing.js"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
