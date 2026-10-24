package com.agv.navdeployer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();

    @Test
    void editorDeepLinksForwardToFrontend() throws Exception {
        for (String path : new String[]{"/editor/1", "/map2/1"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void missingApiAndAssetsDoNotForwardToFrontend() throws Exception {
        for (String path : new String[]{"/api/v2/missing", "/assets/missing.js"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
