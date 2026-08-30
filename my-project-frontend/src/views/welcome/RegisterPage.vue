<script setup>
import {EditPen, Lock, Message, User,} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {reactive, ref} from 'vue'
import router from '@/router'
import {apiAuthAskCode, apiAuthRegister} from '@/net/api/user'

const formRef = ref()
const submitting = ref(false)
const isEmailValid = ref(false)
const coldTime = ref(0)

const form = reactive({
  username: '',
  password: '',
  password_repeat: '',
  email: '',
  code: ''
})

const validateUsername = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请输入用户名'))
  } else if (!/^[a-zA-Z0-9\u4e00-\u9fa5]+$/.test(value)) {
    callback(new Error('用户名不能包含特殊字符，只能是中文/英文'))
  } else {
    callback()
  }
}

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
  username: [
    {validator: validateUsername, trigger: ['blur', 'change']},
    {min: 2, max: 8, message: '用户名的长度必须在2-8个字符之间', trigger: ['blur', 'change']}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
    {min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: ['blur', 'change']}
  ],
  password_repeat: [
    {validator: validatePasswordRepeat, trigger: ['blur', 'change']}
  ],
  email: [
    {required: true, message: '请输入邮件地址', trigger: 'blur'},
    {type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change']}
  ],
  code: [
    {required: true, message: '请输入获取的验证码', trigger: 'blur'}
  ]
}

const onValidate = (prop, isValid) => {
  if (prop === 'email')
    isEmailValid.value = isValid
}

function register() {
  if (submitting.value) return

  formRef.value.validate(isValid => {
    if (!isValid) {
      ElMessage.warning('请完整填写注册表单内容！')
      return
    }
    submitting.value = true
    apiAuthRegister({
      username: form.username,
      password: form.password,
      email: form.email,
      code: form.code
    }, message => {
      submitting.value = false
      ElMessage.warning(message || '注册失败，请稍后重试')
    })
  })
}

const validateEmail = () => apiAuthAskCode(form.email, coldTime)
</script>

<template>
  <div class="register-page">
    <header class="register-header">
      <p class="register-header__eyebrow">加入我们</p>
      <h1>注册校园社区</h1>
      <p>创建你的校园社区账号，与同学分享讨论、活动与校园服务。</p>
    </header>

    <el-form
      ref="formRef"
      class="register-form"
      :model="form"
      :rules="rules"
      label-position="top"
      hide-required-asterisk
      @validate="onValidate"
      @keyup.enter="register"
    >
      <el-form-item prop="username" label="用户名">
        <el-input
          v-model.trim="form.username"
          :prefix-icon="User"
          maxlength="8"
          autocomplete="username"
          placeholder="2-8 个中文或英文字符"
        />
      </el-form-item>

      <el-form-item prop="password" label="密码">
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

      <el-form-item prop="password_repeat" label="确认密码">
        <el-input
          v-model="form.password_repeat"
          :prefix-icon="Lock"
          maxlength="16"
          type="password"
          autocomplete="new-password"
          show-password
          placeholder="请再次输入密码"
        />
      </el-form-item>

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

      <el-button
        class="register-button"
        type="primary"
        :loading="submitting"
        @click="register"
      >
        立即注册
      </el-button>
    </el-form>

    <div class="login-entry">
      <span>已经有账号了？</span>
      <button type="button" class="text-button" @click="router.push('/')">
        直接登录
      </button>
    </div>

    <div class="register-note">
      <span class="register-note__dot" aria-hidden="true"></span>
      注册即代表同意社区友善公约
    </div>
  </div>
</template>

<style scoped>
.register-page {
  width: 100%;
  color: #172033;
}

.register-header__eyebrow {
  margin: 0 0 10px;
  color: #176bff;
  font-size: 13px;
  font-weight: 700;
}

.register-header h1 {
  margin: 0;
  color: #172033;
  font-size: 32px;
  line-height: 1.25;
  letter-spacing: 0;
}

.register-header > p:last-child {
  margin: 13px 0 0;
  color: #778196;
  font-size: 14px;
  line-height: 1.7;
}

.register-form {
  margin-top: 32px;
}

.register-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.register-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 8px;
  padding: 0;
  color: #33415c;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
}

.register-form :deep(.el-input__wrapper) {
  min-height: 50px;
  padding-inline: 15px;
  border-radius: 6px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #dfe5ee inset;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.register-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b9c5d6 inset;
}

.register-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 2px rgba(23, 107, 255, 0.22) inset;
}

.register-form :deep(.el-input__inner) {
  color: #1d2940;
  font-size: 14px;
}

.register-form :deep(.el-input__prefix) {
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

.register-button {
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

.register-button:hover,
.register-button:focus {
  background: #0f5de1;
}

.login-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 24px;
  color: #7d8798;
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

.register-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 36px;
  color: #9aa3b2;
  font-size: 12px;
}

.register-note__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #49a078;
  box-shadow: 0 0 0 4px #e9f5ef;
}

@media (max-width: 840px) {
  .register-header h1 {
    font-size: 29px;
  }

  .register-form {
    margin-top: 26px;
  }

  .register-note {
    margin-top: 30px;
  }
}
</style>
