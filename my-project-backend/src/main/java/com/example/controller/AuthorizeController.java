package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ConfirmResetVO;
import com.example.entity.vo.request.EmailRegisterVO;
import com.example.entity.vo.request.EmailResetVO;
import com.example.service.AccountService;
import com.example.utils.ControllerUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.function.Function;
import java.util.function.Supplier;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthorizeController {

    @Resource
    AccountService accountService;

    @Resource
    ControllerUtils utils;

    @GetMapping("/ask-code")//申请验证码
    public RestBean<Void> askVerifyCode(@RequestParam @Email String email,
                                        @RequestParam @Pattern(regexp = "register|reset|modify") String type,
                                        HttpServletRequest request) {
        return utils.messageHandle(() ->
                accountService.registerEmailVerifyCode(type,email,request.getRemoteAddr()));
    }

    @PostMapping("/register")//注册
    public RestBean<Void> register(@RequestBody @Valid EmailRegisterVO emailRegisterVo) {
        return utils.messageHandle(()->accountService.registerEmailAccount(emailRegisterVo));
    }

    @PostMapping("/reset-confirm")//重置密码_1.确认重置密码
    public RestBean<Void> resetConfirm(@RequestBody @Valid ConfirmResetVO confirmResetVo) {
        return utils.messageHandle(()-> accountService.resetConfirm(confirmResetVo));
    }

    @PostMapping("/reset-password")//重置密码
    public RestBean<Void> resetPassword(@RequestBody @Valid EmailResetVO emailResetVo) {
        return utils.messageHandle(()-> accountService.resetEmailAccountPassword(emailResetVo));
    }
}
