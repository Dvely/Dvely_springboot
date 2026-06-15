package com.example.dvely.common.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.common.exception.GlobalExceptionHandler;
import com.example.dvely.common.exception.NotFoundException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

class ApiResponseContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContractController())
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void wrapsJsonSuccessWithActualHttpStatus() throws Exception {
        mockMvc.perform(get("/contract"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("리소스가 생성되었습니다"))
                .andExpect(jsonPath("$.data.id").value(11));
    }

    @Test
    void usesSameEnvelopeForErrors() throws Exception {
        mockMvc.perform(get("/contract/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("contract not found"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void doesNotWrapEventStreamBody() {
        Object body = new Object();

        Object result = new ApiResponseAdvice().beforeBodyWrite(
                body,
                null,
                MediaType.TEXT_EVENT_STREAM,
                null,
                null,
                null
        );

        assertThat(result).isSameAs(body);
    }

    @Test
    void skipsEndpointMarkedAsRawResponse() throws Exception {
        Method method = ContractController.class.getDeclaredMethod("raw");
        MethodParameter returnType = new MethodParameter(method, -1);

        assertThat(new ApiResponseAdvice().supports(returnType, null)).isFalse();
    }

    @RestController
    private static class ContractController {

        @GetMapping("/contract")
        @ResponseStatus(HttpStatus.CREATED)
        Map<String, Long> create() {
            return Map.of("id", 11L);
        }

        @GetMapping("/contract/missing")
        Map<String, Long> missing() {
            throw new NotFoundException("contract not found");
        }

        @GetMapping("/contract/raw")
        @RawApiResponse
        Map<String, Long> raw() {
            return Map.of("id", 11L);
        }
    }
}
