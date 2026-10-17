package com.procureflow.app.system;

import com.procureflow.app.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import(SecurityConfiguration.class)
class FoundationHttpBoundaryTest {
    @Autowired
    MockMvc mvc;

    @Test
    void onlyNonSensitiveFoundationMetadataIsPublic() throws Exception {
        mvc.perform(get("/api/system"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.name").value("ProcureFlow"))
                .andExpect(jsonPath("$.stage").value("foundation"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void unknownBusinessAndManagementPathsAreNotPublic() throws Exception {
        mvc.perform(get("/api/requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicMetadataDoesNotAllowWrites() throws Exception {
        mvc.perform(post("/api/system")).andExpect(status().isForbidden());
    }
}
