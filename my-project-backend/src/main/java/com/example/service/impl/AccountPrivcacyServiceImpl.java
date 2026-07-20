package com.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.AccountPrivacy;
import com.example.entity.vo.request.PrivacySaveVO;
import com.example.mapper.AccountPrivacyMapper;
import com.example.service.AccountPrivacyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AccountPrivcacyServiceImpl extends ServiceImpl<AccountPrivacyMapper, AccountPrivacy> implements AccountPrivacyService {
    @Override
    @Transactional
    public void savePrivacy(Integer id, PrivacySaveVO privacyVO) {//隐私设置
        AccountPrivacy accountPrivacy = Optional.ofNullable(this.getById(id)).orElse(new AccountPrivacy(id));
        boolean status = privacyVO.isStatus();
        switch (privacyVO.getType()) {
                case "phone"-> accountPrivacy.setPhone(status);
                case "email"-> accountPrivacy.setEmail(status);
                case "wx"-> accountPrivacy.setWx(status);
                case "qq"-> accountPrivacy.setQq(status);
                case "gender"-> accountPrivacy.setGender(status);
        }
        this.saveOrUpdate(accountPrivacy);
    }
    public AccountPrivacy accountPrivacy(Integer id) {//获取隐私设置
        return Optional.ofNullable(this.getById(id)).orElse(new AccountPrivacy(id));
    }
}
