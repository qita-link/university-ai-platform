package com.campus.platform.common.exception;

import com.campus.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证统一异常处理的响应格式，以及内部异常细节不会泄露给客户端。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new DemoController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void businessExceptionMapsToStatusAndCode() throws Exception {
        mvc.perform(get("/demo/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("课程容量已满"));
    }

    @Test
    void notFoundHelper() throws Exception {
        mvc.perform(get("/demo/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("课程不存在"));
    }

    @Test
    void bodyValidationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/demo/body").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].message").value("标题不能为空"));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mvc.perform(post("/demo/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingAndMismatchedParams() throws Exception {
        mvc.perform(get("/demo/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"));
        mvc.perform(get("/demo/param").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void databaseExceptionsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/demo/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(content().string(not(containsString("uk_user_username"))))
                .andExpect(content().string(not(containsString("INSERT"))));
        mvc.perform(get("/demo/integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("fk_"))));
    }

    @Test
    void unexpectedExceptionReturnsGenericMessage() throws Exception {
        mvc.perform(get("/demo/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("password=hunter2"))))
                .andExpect(content().string(not(containsString("NullPointer"))));
    }

    record DemoBody(@NotBlank(message = "标题不能为空") @Size(max = 10) String title) {
    }

    @RestController
    static class DemoController {

        @GetMapping("/demo/business")
        ApiResponse<Void> business() {
            throw BusinessException.rule("课程容量已满");
        }

        @GetMapping("/demo/not-found")
        ApiResponse<Void> notFound() {
            throw BusinessException.notFound("课程");
        }

        @PostMapping("/demo/body")
        ApiResponse<String> body(@Valid @RequestBody DemoBody body) {
            return ApiResponse.ok(body.title());
        }

        @GetMapping("/demo/param")
        ApiResponse<Long> param(@RequestParam Long id) {
            return ApiResponse.ok(id);
        }

        @GetMapping("/demo/duplicate")
        ApiResponse<Void> duplicate() {
            throw new DuplicateKeyException("Duplicate entry 'x' for key 'uk_user_username' INSERT INTO sys_user");
        }

        @GetMapping("/demo/integrity")
        ApiResponse<Void> integrity() {
            throw new DataIntegrityViolationException("Cannot add or update a child row: fk_user_org_unit");
        }

        @GetMapping("/demo/boom")
        ApiResponse<Void> boom() {
            throw new NullPointerException("password=hunter2");
        }
    }
}
