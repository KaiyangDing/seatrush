package com.seatrush.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The pages are served to anonymous visitors; logging in happens on the page itself. */
@SpringBootTest
@AutoConfigureMockMvc
class StaticPagesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootShowsTheSeatMap() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void seatMapPageAndItsScriptsArePublic() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/seatmap.js")));
        mockMvc.perform(get("/js/common.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/seatmap.js")).andExpect(status().isOk());
        mockMvc.perform(get("/css/seatrush.css")).andExpect(status().isOk());
    }

    @Test
    void adminPageIsPublicButItsApiIsNot() throws Exception {
        // The page only shows a login box to anybody who is not an admin
        mockMvc.perform(get("/admin.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/admin.js")));
        mockMvc.perform(get("/js/admin.js")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/lock-strategy")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherPathsStillNeedALogin() throws Exception {
        mockMvc.perform(get("/secret.html")).andExpect(status().isUnauthorized());
    }
}
