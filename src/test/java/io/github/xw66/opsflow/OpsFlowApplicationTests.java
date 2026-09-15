package io.github.xw66.opsflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OpsFlowApplicationTests extends MySqlTestBase {
    @Autowired
    private MockMvc mvc;

    @Test
    void exposesServiceInformationAndHealth() throws Exception {
        mvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("OpsFlow"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void servesSwaggerAndRealOpenApiDefinition() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/system/info'].get").exists());
    }

    @Test
    void rejectsAnonymousAccessWithoutRedirectOrSession() throws Exception {
        for (String path : new String[]{"/api/tickets", "/actuator/env", "/actuator/health/liveness"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsUnconfiguredRoutesEvenForAuthenticatedUsers() throws Exception {
        mvc.perform(get("/api/not-configured")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsWritesToPublicReadEndpoint() throws Exception {
        mvc.perform(post("/api/system/info")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

}
