package io.github.xw66.opsflow.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void preservesBusinessHttpStatus() throws Exception {
        mvc.perform(get("/probe/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void normalizesInvalidBodyAndMalformedJson() throws Exception {
        for (String body : new String[]{"{}", "{\"title\":\" \"}", "{"}) {
            mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
        }
    }

    @Test
    void preservesMethodAndContentTypeErrors() throws Exception {
        mvc.perform(put("/probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_405"));
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("HTTP_415"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mvc.perform(get("/probe/error")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @RestController
    static class ErrorProbeController {
        @PostMapping("/probe")
        ApiResponse<Input> validate(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "工单已被其他人修改");
        }

        @GetMapping("/probe/error")
        void error() {
            throw new IllegalStateException("仅用于测试的内部错误信息");
        }
    }

    record Input(@NotBlank String title) { }
}
