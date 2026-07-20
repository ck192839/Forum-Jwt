package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountDetails;
import com.example.entity.vo.request.ChangePasswordVO;
import com.example.entity.vo.request.DetailsSaveVO;
import com.example.entity.vo.request.ModifyEmailVO;
import com.example.entity.vo.request.PrivacySaveVO;
import com.example.entity.vo.response.AccountDetailsVO;
import com.example.entity.vo.response.AccountPrivacyVO;
import com.example.entity.vo.response.AccountVO;
import com.example.service.AccountDetailsService;
import com.example.service.AccountPrivacyService;
import com.example.service.AccountService;
import com.example.utils.Const;
import com.example.utils.ControllerUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class AccountController {

    @Resource
    private AccountService accountService;

    @Resource
    private AccountPrivacyService accountPrivacyService;

    @Resource
    private AccountDetailsService accountDetailsService;

    @Resource
    ControllerUtils utils;
    @GetMapping("/info")
    public RestBean<AccountVO> info(@RequestAttribute(Const.ATTR_USER_ID) Integer id) {
        Account account = accountService.findAccountById(id);
        return RestBean.success(account.asViewObject(AccountVO.class));
    }

    @GetMapping("/details")
    public RestBean<AccountDetailsVO> details(@RequestAttribute(Const.ATTR_USER_ID) Integer id) {
        AccountDetails accountDetails = Optional
                .ofNullable(accountDetailsService.findAccountDetailsById(id))
                .orElseGet(AccountDetails::new);
        return RestBean.success(accountDetails.asViewObject(AccountDetailsVO.class));
    }

    @PostMapping("/save-details")
    public RestBean<Boolean> saveDetails(@RequestAttribute(Const.ATTR_USER_ID) Integer id,
                                         @RequestBody DetailsSaveVO detailsSaveVO) {
        boolean success = accountDetailsService.saveAccountDetails(id, detailsSaveVO);
        return success?RestBean.success():RestBean.failure(400,"此用户名已被使用");
    }
    @PostMapping("/modify-email")
    public RestBean<Void> modifyEmail(@RequestAttribute(Const.ATTR_USER_ID) Integer id,
                                  @RequestBody  @Valid ModifyEmailVO modifyEmailVO) {
        return utils.messageHandle(() -> accountService.modifyEmail(id,modifyEmailVO));
    }

    @PostMapping("/change-password")
    public RestBean<Void> changePassword(@RequestAttribute(Const.ATTR_USER_ID) Integer id,
                                          @RequestBody  @Valid ChangePasswordVO changePasswordVO) {
    return utils.messageHandle(() -> accountService.changePassword(id,changePasswordVO));
    }
    @PostMapping("/save-privacy")
    public RestBean<Void> savePrivacy(@RequestAttribute(Const.ATTR_USER_ID) Integer id,
                                        @RequestBody  @Valid PrivacySaveVO privacyVO) {
        accountPrivacyService.savePrivacy(id,privacyVO);
        return RestBean.success();
    }

    @GetMapping("/privacy")
    public RestBean<AccountPrivacyVO> privacy(@RequestAttribute(Const.ATTR_USER_ID) Integer id) {
        return RestBean.success(accountPrivacyService.accountPrivacy(id).asViewObject(AccountPrivacyVO.class));
    }



}
