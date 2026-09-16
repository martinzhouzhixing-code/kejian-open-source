# 课间开源版 2.4.0

Android 课表、按周课程备注、录音笔记与个人 API Key 驱动的 AI 助手。

- 本周周末无课时自动隐藏周末列；编辑模式仍可添加周末课程。
- 在设置中选择每日显示时间，例如 08:00–20:00；范围外有课时自动扩展。
- 课程格优先显示名称与地点，点击展开完整详情和本周备注。
- 桌面组件照片等比裁切，避免拉伸；教程文案与演示分区。
- 无需注册或登录。设置中填写个人 API Key；没有密钥仍可使用本地功能。
- 图片识别、日程命令、录音转写与总结、笔记追问和思维导图保留。

## Android 构建

使用 JDK 17、Android SDK 37 和 Android Studio 支持的 Gradle 环境：

```sh
cd android
./gradlew assemblePublicDebug testPublicDebugUnitTest
```

Windows 使用 `gradlew.bat`。应用包名为 `app.kejian.opensource`，可与正式版同时安装。发布版本需要自行配置签名；仓库不包含正式版签名文件。调试 APK 不能用于覆盖正式版应用。

## 部署自己的 AI 服务器

1. 将 `server/.env.example` 复制为 `server/.env`，生成随机 `KEJIAN_APP_SECRET`，填写支持视觉与结构化工具输出的模型和 API 地址。文本总结模型可单独配置。密钥无需写入服务器环境变量。
2. 运行 `docker compose up --build -d`。
3. 用 Nginx 或 Caddy 将自己的 HTTPS 域名反向代理到 `127.0.0.1:8401`；设置上传上限至少 36 MB、读取超时至少 300 秒。`KEJIAN_PUBLIC_ORIGIN` 必须是语音服务可访问的同一 HTTPS 域名。签名音频地址不要写入访问日志。
4. 在手机设置 → 个人 API Key 填写服务器域名和该服务商的 API Key，点击“验证并保存”。验证会读取服务商模型列表，不发送课程内容。服务商必须实现兼容的 `/models` 和 `/chat/completions` 接口。
5. 使用豆包录音转写时，另外填写已开通 `volc.seedasr.auc` 的语音 API Key。转写和文本模型可能属于不同服务商，因此不能保证一个 Key 同时适用。

服务器只开放 BYOK AI 路由，账号注册、登录、管理后台、会员购买和正式版更新接口均不可用。不要以 `app:app` 启动；入口为 `gateway:app`。

## 文件与任务流程

图片或录音上传到自托管服务器，服务器调用配置的 AI，手机取回结果。图片使用一次整图识别，导入前必须预览确认。PDF/Excel 在手机转换成图片后上传。长录音支持分段传输和断点续传；完整文件仍作为一个转写任务提交。语音服务按签名 URL 读取服务器上的临时音频，确认下载超时后最多重新提交三次；查询网络中断会继续查询原任务，避免重复提交。

手机密钥通过 Android Keystore 加密保存，不进入课表备份。密钥会通过 HTTPS 发给你填写的服务器，因此只使用自己部署或信任的服务器。服务器仅在内存保留任务凭据，数据库使用加盐 HMAC 标识隔离数据。使用单个 Uvicorn worker；重启后客户端查询会重新提供凭据，若客户端未返回，任务可能需要手动重试。原录音仍保留在手机。

服务商按自己的规则计费。密钥验证成功不代表模型一定支持所需的视觉、工具调用或语音能力，具体调用失败会保留本机资料并显示错误。更换 Key 会切换服务器上的任务身份，建议等待已有任务完成。

## 验证

```sh
cd server
python -m pip install -r requirements.txt pytest
python -m pytest test_gateway.py test_audio_summary.py test_audio_recovery240.py -q
```

测试使用隔离数据库和模拟 AI，覆盖无密钥、无账号接口、无效密钥、多用户凭据隔离及音频下载重试。Android 单元测试覆盖周末显示、时间范围和每周备注往返保存。真实服务商调用需使用自己的有效 Key 验证。

## 许可

项目代码采用 MIT；第三方依赖及图片来源遵循各自许可，见 [素材来源](android/ASSET-CREDITS.md)。
