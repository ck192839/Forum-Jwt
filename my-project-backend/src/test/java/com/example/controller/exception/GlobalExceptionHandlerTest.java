package com.example.controller.exception;

import com.example.entity.RestBean;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resolvesBeanValidationException() {
        assertEquals(400, handler.handleValidationException(new ValidationException("bad")).code());
    }

    @Test
    void resolvesMethodArgumentNotValidWithFieldMessage() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "vo");
        bindingResult.addError(new org.springframework.validation.FieldError("vo", "type", "主题类型非法"));
        MethodArgumentNotValidException e =
                new MethodArgumentNotValidException(null, bindingResult);

        RestBean<Void> result = handler.handleMethodArgumentNotValid(e);

        assertEquals(400, result.code());
        assertEquals("主题类型非法", result.message());
    }

    @Test
    void resolvesMethodArgumentNotValidWithoutFieldError() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "vo");
        MethodArgumentNotValidException e =
                new MethodArgumentNotValidException(null, bindingResult);

        RestBean<Void> result = handler.handleMethodArgumentNotValid(e);

        assertEquals(400, result.code());
        assertEquals("请求参数有误", result.message());
    }

    @SuppressWarnings("unchecked")
    @Test
    void resolvesConstraintViolationWithViolationMessage() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("邮箱格式不正确");
        ConstraintViolationException e = new ConstraintViolationException(Set.of(violation));

        RestBean<Void> result = handler.handleConstraintViolation(e);

        assertEquals(400, result.code());
        assertEquals("邮箱格式不正确", result.message());
    }

    @Test
    void resolvesUnreadableRequestBody() {
        RestBean<Void> result = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])));

        assertEquals(400, result.code());
        assertEquals("请求体格式有误", result.message());
    }

    @Test
    void fallbackHidesInternalDetails() {
        RuntimeException e = new RuntimeException("jdbc password = secret", new IllegalStateException("stack detail"));

        RestBean<Void> result = handler.handleException(e);

        assertEquals(500, result.code());
        assertEquals("服务器内部错误，请稍后再试", result.message());
        assertFalse(result.message().contains("secret"));
        assertFalse(result.message().contains("IllegalState"));
    }
}
