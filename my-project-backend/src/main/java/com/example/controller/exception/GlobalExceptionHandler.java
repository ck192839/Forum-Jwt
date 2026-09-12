package com.example.controller.exception;

import com.example.entity.RestBean;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ValidationException.class)
    public RestBean<Void> handleValidationException(ValidationException e){
        log.warn("Resolve[{}:{}]", e.getClass().getName(),e.getMessage());
        return RestBean.failure(400,"请求参数有误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public RestBean<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = Optional.ofNullable(e.getBindingResult().getFieldError())
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数有误");
        log.warn("Resolve[{}:{}]", e.getClass().getName(), message);
        return RestBean.failure(400, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public RestBean<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("请求参数有误");
        log.warn("Resolve[{}:{}]", e.getClass().getName(), message);
        return RestBean.failure(400, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public RestBean<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Resolve[{}:{}]", e.getClass().getName(), e.getMessage());
        return RestBean.failure(400, "请求体格式有误");
    }

    @ExceptionHandler(Exception.class)
    public RestBean<Void> handleException(Exception e) {
        log.error("Resolve[{}:{}]", e.getClass().getName(), e.getMessage(), e);
        return RestBean.failure(500, "服务器内部错误，请稍后再试");
    }
}
