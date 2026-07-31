<script setup>
import {ArrowRight, Lock, User} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {inject, reactive, ref} from 'vue'
import router from '@/router'
import {login} from '@/net'
import {apiUserInfo} from '@/net/api/user'

const formRef = ref()
const submitting = ref(false)
const loading = inject('userLoading')

const form = reactive({
  username: '',
  password: '',
  remember: false
})

const rules = {
  username: [
    {required: true, message: '请输入用户名或邮箱', trigger: 'blur'}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'}
  ]
}

function userLogin() {
  if (submitting.value) return

  formRef.value.validate(isValid => {
    if (!isValid) return

    submitting.value = true
    login(form.username, form.password, form.remember, () => {
      apiUserInfo(loading)
      router.push('/index')
    }, message => {
      submitting.value = false
      ElMessage.warning(message || '登录失败，请检查账号和密码')
    })
  })
}
</script>

<template>
  <div class="login-page">
    <header class="login-header">
      <p class="login-header__eyebrow">欢迎回来</p>
      <h1>登录校园社区</h1>
      <p>使用你的校园社区账号继续访问论坛与校园服务。</p>
    </header>

    <el-form
      ref="formRef"
      class="login-form"
      :model="form"
      :rules="rules"
      label-position="top"
      hide-required-asterisk
      @keyup.enter="userLogin"
    >
      <el-form-item prop="username" label="账号">
        <el-input
          v-model.trim="form.username"
          :prefix-icon="User"
          maxlength="40"
          autocomplete="username"
          placeholder="用户名或邮箱"
        />
      </el-form-item>

      <el-form-item prop="password" label="密码">
        <el-input
          v-model="form.password"
          :prefix-icon="Lock"
          maxlength="20"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
          show-password
        />
      </el-form-item>

      <div class="form-options">
        <el-checkbox v-model="form.remember">保持登录</el-checkbox>
        <button type="button" class="text-button" @click="router.push('/forget')">
          忘记密码？
        </button>
      </div>

      <el-button
        class="login-button"
        type="primary"
        :loading="submitting"
        @click="userLogin"
      >
        <span>登录</span>
        <el-icon v-if="!submitting"><ArrowRight /></el-icon>
      </el-button>
    </el-form>

    <div class="register-entry">
      <span>第一次来到校园社区？</span>
      <button type="button" class="text-button" @click="router.push('/register')">
        创建账号
      </button>
    </div>

    <div class="login-note">
      <span class="login-note__dot" aria-hidden="true"></span>
      账号信息仅用于社区身份验证
    </div>
  </div>
</template>

<style scoped>
.login-page {
  width: 100%;
  color: #172033;
}

.login-header__eyebrow {
  margin: 0 0 10px;
  color: #176bff;
  font-size: 13px;
  font-weight: 700;
}

.login-header h1 {
  margin: 0;
  color: #172033;
  font-size: 32px;
  line-height: 1.25;
  letter-spacing: 0;
}

.login-header > p:last-child {
  margin: 13px 0 0;
  color: #778196;
  font-size: 14px;
  line-height: 1.7;
}

.login-form {
  margin-top: 38px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 23px;
}

.login-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  padding: 0;
  color: #33415c;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 50px;
  padding-inline: 15px;
  border-radius: 6px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #dfe5ee inset;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b9c5d6 inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 2px rgba(23, 107, 255, 0.22) inset;
}

.login-form :deep(.el-input__inner) {
  color: #1d2940;
  font-size: 14px;
}

.login-form :deep(.el-input__prefix) {
  color: #7b8799;
  font-size: 17px;
}

.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: -3px;
}

.form-options :deep(.el-checkbox__label) {
  color: #58657a;
  font-size: 13px;
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

.login-button {
  width: 100%;
  height: 50px;
  display: flex;
  margin-top: 28px;
  border: 0;
  border-radius: 6px;
  background: #176bff;
  font-size: 15px;
  font-weight: 650;
  box-shadow: 0 10px 24px rgba(23, 107, 255, 0.2);
}

.login-button:hover,
.login-button:focus {
  background: #0f5de1;
}

.login-button :deep(span) {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
}

.register-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 26px;
  color: #7d8798;
  font-size: 13px;
}

.login-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 42px;
  color: #9aa3b2;
  font-size: 12px;
}

.login-note__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #49a078;
  box-shadow: 0 0 0 4px #e9f5ef;
}

@media (max-width: 840px) {
  .login-header h1 {
    font-size: 29px;
  }

  .login-form {
    margin-top: 32px;
  }

  .login-note {
    margin-top: 34px;
  }
}
</style>
