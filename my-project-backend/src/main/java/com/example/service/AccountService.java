package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.Account;
import com.example.entity.vo.request.*;
import org.springframework.security.core.userdetails.UserDetailsService;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);
    Account findAccountById(Integer id);
    String registerEmailVerifyCode(String type,String email,String ip);
    String registerEmailAccount(EmailRegisterVO emailRegisterVo);
    String resetConfirm(ConfirmResetVO confirmResetVo);
    String resetEmailAccountPassword(EmailResetVO emailResetVo);
    String modifyEmail(Integer id, ModifyEmailVO modifyEmailVo);
    String changePassword(int id, ChangePasswordVO vo);
    void modifyPassword(int id, String newPassword);//管理员改密码

}
