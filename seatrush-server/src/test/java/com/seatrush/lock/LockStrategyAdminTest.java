package com.seatrush.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class LockStrategyAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SeatLockStrategies lockStrategies;

    @Autowired
    private LockProperties lockProperties;

    @AfterEach
    void restoreStrategy() {
        // The strategy is shared by the whole application context, so put it back for the other tests
        lockStrategies.use(lockProperties.strategy());
    }

    @Test
    void adminSeesTheCurrentStrategyAndAllChoices() throws Exception {
        mockMvc.perform(get("/api/admin/lock-strategy").with(as("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("pessimistic"))
                .andExpect(jsonPath("$.available[0]").value("pessimistic"))
                .andExpect(jsonPath("$.available[1]").value("optimistic"))
                .andExpect(jsonPath("$.available[2]").value("in-memory"))
                .andExpect(jsonPath("$.available[3]").value("redis"));
    }

    @Test
    void adminSwitchesTheStrategyForNewHolds() throws Exception {
        switchTo("redis", "ROLE_ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("redis"));

        assertThat(lockStrategies.current().name()).isEqualTo("redis");
    }

    @Test
    void unknownStrategyIsRejectedWithTheChoices() throws Exception {
        switchTo("magic", "ROLE_ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.available.length()").value(4));

        assertThat(lockStrategies.current().name()).isEqualTo("pessimistic");
    }

    @Test
    void blankStrategyIsRejected() throws Exception {
        switchTo(" ", "ROLE_ADMIN").andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotSwitch() throws Exception {
        switchTo("optimistic", "ROLE_CUSTOMER").andExpect(status().isForbidden());

        assertThat(lockStrategies.current().name()).isEqualTo("pessimistic");
    }

    @Test
    void anonymousUserCannotSeeTheStrategy() throws Exception {
        mockMvc.perform(get("/api/admin/lock-strategy")).andExpect(status().isUnauthorized());
    }

    private ResultActions switchTo(String strategy, String role) throws Exception {
        return mockMvc.perform(put("/api/admin/lock-strategy")
                .with(as(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\": \"" + strategy + "\"}"));
    }

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority(role));
    }
}
