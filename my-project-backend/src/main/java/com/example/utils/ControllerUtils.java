package com.example.utils;

import com.example.entity.RestBean;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
@Component
public class ControllerUtils {
    public <T> RestBean<T> messageHandle(Supplier<String> action) {
        String message = action.get();
        return message==null?RestBean.success():RestBean.failure(400, message);
    }

}
