<script setup>
import {EditPen, Lock, Message} from '@element-plus/icons-vue'
import {reactive, ref} from 'vue'
import router from '@/router'
import {apiAuthAskCode, apiAuthResetPassword, apiAuthRestConfirm} from '@/net/api/user'

const active = ref(0)

const form = reactive({
  email: '',
  code: '',
  password: '',
  password_repeat: ''
})

const validatePasswordRepeat = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  email: [
    {required: true, message: '请输入邮件地址', trigger: 'blur'},
    {type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change']}
  ],
  code: [
    {required: true, message: '请输入获取的验证码', trigger: 'blur'}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
    {min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: 'blur'}
  ],
  password_repeat: [
    {validator: validatePasswordRepeat, trigger: ['blur', 'change']}
  ]
}

const formRef = ref()
const isEmailValid = ref(false)
const coldTime = ref(0)

const onValidate = (prop, isValid) => {
  if (prop === 'email')
    isEmailValid.value = isValid
}

const validateEmail = () => apiAuthAskCode(form.email, coldTime, 'reset')

const confirmReset = () => {
  formRef.value.validate(isValid => {
    if (isValid)
      apiAuthRestConfirm({
        email: form.email,
        code: form.code
      }, active)
  })
}

const doReset = () => {
  formRef.value.validate(isValid => {
    if (isValid)
      apiAuthResetPassword({
        email: form.email,
        code: form.code,
        password: form.password
      })
  })
}
</script>

<template>
  <div class="forget-page">
    <header class="forget-header">
      <p class="forget-header__eyebrow">找回账号</p>
      <h1>重置密码</h1>
      <p>通过注册邮箱验证身份后即可设置新密码，整个过程不会超过两分钟。</p>
    </header>

    <el-steps class="forget-steps" :active="active" align-center finish-status="success">
      <el-step title="验证邮箱"/>
      <el-step title="设置新密码"/>
    </el-steps>

    <transition name="auth-fade" mode="out-in">
      <el-form
        v-if="active === 0"
        key="step-email"
        ref="formRef"
        class="forget-form"
        :model="form"
        :rules="rules"
        label-position="top"
        hide-required-asterisk
        @validate="onValidate"
        @keyup.enter="confirmReset"
      >
        <el-form-item prop="email" label="邮箱地址">
          <el-input
            v-model.trim="form.email"
            :prefix-icon="Message"
            type="email"
            autocomplete="email"
            placeholder="name@example.com"
          />
        </el-form-item>

        <el-form-item prop="code" label="邮箱验证码">
          <div class="code-field">
            <el-input
              v-model.trim="form.code"
              class="code-field__input"
              :prefix-icon="EditPen"
              maxlength="6"
              placeholder="请输入验证码"
            />
            <el-button
              class="code-field__button"
              :disabled="!isEmailValid || coldTime > 0"
              @click="validateEmail"
            >
              {{ coldTime > 0 ? `重新发送 ${coldTime}s` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-button class="forget-button" type="primary" @click="confirmReset">
          验证邮箱
        </el-button>
      </el-form>

      <el-form
        v-else
        key="step-password"
        ref="formRef"
        class="forget-form"
        :model="form"
        :rules="rules"
        label-position="top"
        hide-required-asterisk
        @keyup.enter="doReset"
      >
        <el-form-item prop="password" label="新密码">
          <el-input
            v-model="form.password"
            :prefix-icon="Lock"
            maxlength="16"
            type="password"
            autocomplete="new-password"
            show-password
            placeholder="6-16 个字符"
          />
        </el-form-item>

        <el-form-item prop="password_repeat" label="确认新密码">
          <el-input
            v-model="form.password_repeat"
            :prefix-icon="Lock"
            maxlength="16"
            type="password"
            autocomplete="new-password"
            show-password
            placeholder="请再次输入新密码"
          />
        </el-form-item>

        <el-button class="forget-button" type="primary" @click="doReset">
          立即重置密码
        </el-button>

        <button type="button" class="text-button forget-back" @click="active = 0">
          返回上一步
        </button>
      </el-form>
    </transition>

    <div class="forget-note">
      <span class="forget-note__dot" aria-hidden="true"></span>
      验证码发送至注册邮箱，10 分钟内有效
    </div>

    <div class="login-entry">
      <span>想起来了？</span>
      <button type="button" class="text-button" @click="router.push('/')">
        直接登录
      </button>
    </div>
  </div>
</template>

<style scoped>
.forget-page {
  width: 100%;
  color: #172033;
}

.forget-header__eyebrow {
  margin: 0 0 10px;
  color: #176bff;
  font-size: 13px;
  font-weight: 700;
}

.forget-header h1 {
  margin: 0;
  color: #172033;
  font-size: 32px;
  line-height: 1.25;
  letter-spacing: 0;
}

.forget-header > p:last-child {
  margin: 13px 0 0;
  color: #778196;
  font-size: 14px;
  line-height: 1.7;
}

.forget-steps {
  margin-top: 30px;
}

.forget-steps :deep(.el-step__title) {
  color: #58657a;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
}

.forget-steps :deep(.el-step__title.is-wait) {
  color: #9aa3b2;
  font-weight: 500;
}

.forget-steps :deep(.el-step__head.is-finish .el-step__line) {
  border-color: rgba(23, 107, 255, 0.35);
}

.forget-steps :deep(.el-step__icon) {
  border: 1px solid #dfe5ee;
  background: #f8fafc;
  color: #7b8799;
}

.forget-steps :deep(.el-step__head.is-finish .el-step__icon),
.forget-steps :deep(.el-step__head.is-process .el-step__icon) {
  color: #176bff;
  border-color: rgba(23, 107, 255, 0.45);
  background: #eef4ff;
}

.forget-form {
  margin-top: 28px;
}

.forget-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.forget-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  padding: 0;
  color: #33415c;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
}

.forget-form :deep(.el-input__wrapper) {
  min-height: 50px;
  padding-inline: 15px;
  border-radius: 6px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #dfe5ee inset;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.forget-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b9c5d6 inset;
}

.forget-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 2px rgba(23, 107, 255, 0.22) inset;
}

.forget-form :deep(.el-input__inner) {
  color: #1d2940;
  font-size: 14px;
}

.forget-form :deep(.el-input__prefix) {
  color: #7b8799;
  font-size: 17px;
}

.code-field {
  width: 100%;
  display: flex;
  gap: 10px;
}

.code-field__input {
  flex: 1;
  min-width: 0;
}

.code-field__button {
  flex: 0 0 auto;
  height: 50px;
  padding: 0 14px;
  color: #176bff;
  background: #eef4ff;
  border: 1px solid rgba(23, 107, 255, 0.35);
  border-radius: 6px;
  font-weight: 650;
}

.code-field__button:hover:not(:disabled) {
  color: #0f5de1;
  background: #e2ecff;
  border-color: rgba(23, 107, 255, 0.55);
}

.code-field__button:disabled {
  color: #9aa7bd;
  background: #f1f4f9;
  border-color: #e2e8f0;
}

.forget-button {
  width: 100%;
  height: 50px;
  display: flex;
  margin-top: 10px;
  border: 0;
  border-radius: 6px;
  background: #176bff;
  font-size: 15px;
  font-weight: 650;
  box-shadow: 0 10px 24px rgba(23, 107, 255, 0.2);
}

.forget-button:hover,
.forget-button:focus {
  background: #0f5de1;
}

.forget-back {
  display: block;
  margin: 16px auto 0;
}

.text-button {
  border: 0;
  padding: 3px 0;
  background: transparent;
  color: #176bff;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}

.text-button:hover {
  color: #0f53c8;
}

.text-button:focus-visible {
  border-radius: 3px;
  outline: 2px solid rgba(23, 107, 255, 0.35);
  outline-offset: 3px;
}

.forget-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 36px;
  color: #9aa3b2;
  font-size: 12px;
}

.forget-note__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #49a078;
  box-shadow: 0 0 0 4px #e9f5ef;
}

.login-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 18px;
  color: #7d8798;
  font-size: 13px;
}

@media (max-width: 840px) {
  .forget-header h1 {
    font-size: 29px;
  }

  .forget-form {
    margin-top: 24px;
  }

  .forget-note {
    margin-top: 30px;
  }
}

.auth-fade-enter-active,
.auth-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.auth-fade-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

.auth-fade-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
