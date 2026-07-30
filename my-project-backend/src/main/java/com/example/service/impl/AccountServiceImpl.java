package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountDetails;
import com.example.entity.dto.AccountPrivacy;
import com.example.entity.vo.request.*;
import com.example.mapper.AccountDetailsMapper;
import com.example.mapper.AccountMapper;
import com.example.mapper.AccountPrivacyMapper;
import com.example.service.AccountService;
import com.example.service.EmailService;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import com.example.utils.JwtUtils;
import jakarta.annotation.Resource;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {
    @Resource
    FlowUtils flowUtils;

    @Resource
    EmailService emailService;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    PasswordEncoder passwordEncoder;

    @Resource
    AccountPrivacyMapper accountPrivacyMapper;

    @Resource
    AccountDetailsMapper accountDetailsMapper;
    @Resource
    JwtUtils jwtUtils;

    @Value("${spring.web.verify.mail-limit:60}")
    private int mailVerifyLimit;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = findAccountByNameOrEmail(username);
        if (account == null)
            throw new UsernameNotFoundException("用户名或密码错误");

        return User
                .withUsername(username)
                .password(account.getPassword())
                .roles(account.getRole())
                .build();
    }

    /**
     * 生成注册验证码存入Redis中，并将邮件发送请求提交到消息队列等待发送
     *
     * @param type    类型
     * @param email   邮件地址
     * @param address 请求IP地址
     * @return 操作结果，null表示正常，否则为错误原因
     */
    @Override
    public String registerEmailVerifyCode(String type, String email, String address) {// 申请验证码,todo:添加验证码类型
        synchronized (address.intern()) {
            if (!this.verifyLimit(address))
                return "验证码发送过快";
            Random random = new Random();
            int code = random.nextInt(900000) + 100000;
            emailService.sendVerifyEmail(type, email, code);
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, String.valueOf(code), 3, TimeUnit.MINUTES);
            return null;
        }
    }

    /**
     * 邮件验证码注册账号操作，需要检查验证码是否正确以及邮箱、用户名是否存在重名
     *
     * @param emailRegisterVo 注册基本信息
     * @return 操作结果，null表示正常，否则为错误原因
     */

    @Override
    @Transactional
    public String registerEmailAccount(EmailRegisterVO emailRegisterVo) {// 注册账号,todo:添加验证码类型
        String email = emailRegisterVo.getEmail();
        String username = emailRegisterVo.getUsername();
        String key = Const.VERIFY_EMAIL_DATA + email;
        String code = stringRedisTemplate.opsForValue().get(key);
        if (code == null)
            return "请先获取验证码！";
        if (!code.equals(emailRegisterVo.getCode()))
            return "验证码错误,请重新输入！";
        if (this.existsAccountByEmail(email))
            return "邮箱已被他人注册！";
        if (this.existsAccountByUsername(username))
            return "此用户名已被他人注册！";
        String password = passwordEncoder.encode(emailRegisterVo.getPassword());
        Account account = new Account(null, username, password, email, Const.ROLE_DEFAULT, null, new Date(), false,
                false);
        if (!this.save(account))
            return "注册失败！请联系管理员";
        else {
            stringRedisTemplate.delete(key);
            accountPrivacyMapper.insert(new AccountPrivacy(account.getId()));
            AccountDetails details = new AccountDetails();// details无默认值，全参构造太麻烦
            details.setId(account.getId());
            accountDetailsMapper.insert(details);
            return "注册成功！";
        }
    }

    @Override
    public String resetEmailAccountPassword(EmailResetVO emailResetVo) {// 重置密码_2.重置密码
        String email = emailResetVo.getEmail();
        String verify = this.resetConfirm(new ConfirmResetVO(email, emailResetVo.getCode()));
        if (verify != null)
            return verify;
        String password = passwordEncoder.encode(emailResetVo.getPassword());
        boolean update = this.update().eq("email", email).set("password", password).update();
        if (update)
            stringRedisTemplate.delete(Const.VERIFY_EMAIL_DATA + email);
        return null;

    }

    @Override
    public String resetConfirm(ConfirmResetVO confirmResetVo) {// 重置密码_1.确认验证码
        String email = confirmResetVo.getEmail();
        String code = this.getEmailVerifyCode(email);
        if (code == null)
            return "请先获取验证码";
        if (!confirmResetVo.getCode().equals(code))
            return "验证码错误";
        return null;
    }

    @Override
    public String modifyEmail(Integer id, ModifyEmailVO modifyEmailVo) {// 修改邮箱
        String email = modifyEmailVo.getEmail();
        String code = this.getEmailVerifyCode(email);
        if (code == null)
            return "请先获取验证码";
        if (!code.equals(modifyEmailVo.getCode()))
            return "验证码错误";
        Account account = this.findAccountByNameOrEmail(email);
        if (account == null) {
            return "该邮箱未注册任何账户";
        }
        if (account.getId().equals(id)) {
            return "不能绑定自己的邮箱！";
        } else if (this.existsAccountByEmail(email)) {
            return "邮箱已被他人注册！";
        }
        this.update()
                .eq("id", id)
                .set("email", email)
                .update();
        return null;
    }

    @Override
    public String changePassword(int id, ChangePasswordVO vo) {
        String password = this.query().eq("id", id).one().getPassword();
        if (!passwordEncoder.matches(vo.getPassword(), password))
            return "原密码错误，请重新输入！";
        boolean success = this.update()
                .eq("id", id)
                .set("password", passwordEncoder.encode(vo.getNew_password()))
                .update();
        return success ? null : "未知错误，请联系管理员";
    }

    @Override
    public void modifyPassword(int id, String newPassword) {// 管理员改密码
        Account account = this.findAccountById(id);
        this.update()
                .eq("id", id)
                .set("password", passwordEncoder.encode(newPassword))
                .update();
    }

    public Account findAccountByNameOrEmail(String text) {
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .one();
    }

    public Account findAccountById(Integer id) {
        return this.query().eq("id", id).one();
    }

    private boolean existsAccountByEmail(String email) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("email", email));
    }

    private boolean existsAccountByUsername(String username) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("username", username));
    }

    private String getEmailVerifyCode(String email) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        return stringRedisTemplate.opsForValue().get(key);
    }

    private boolean verifyLimit(String ip) {
        String key = Const.VERIFY_EMAIL_LIMIT + ip;
        return flowUtils.limitOnceCheck(key, mailVerifyLimit);
    }
}
