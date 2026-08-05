package com.example.agent.api;

import com.example.agent.run.AgentRunConflictException;
import com.example.agent.session.AgentSessionNotFoundException;
import com.example.entity.RestBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentControllerAdvice {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<RestBean<Void>> badRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "Invalid Agent request");
    }

    @ExceptionHandler({AgentSessionNotFoundException.class, AgentRunNotFoundException.class})
    public ResponseEntity<RestBean<Void>> notFound(RuntimeException exception) {
        return response(HttpStatus.NOT_FOUND, "Agent session or run not found");
    }

    @ExceptionHandler(AgentRunConflictException.class)
    public ResponseEntity<RestBean<Void>> conflict(AgentRunConflictException exception) {
        return response(HttpStatus.CONFLICT, "An Agent run is already active for this session");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestBean<Void>> serverError(Exception exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to start or restore Agent operation");
    }

    private ResponseEntity<RestBean<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(RestBean.failure(status.value(), message));
    }
}
