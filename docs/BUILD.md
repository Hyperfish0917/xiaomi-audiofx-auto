# 从源码构建

## 环境

- Linux x86_64（Windows 可使用 WSL）。
- Java 17 或更新版本，包含 java 和 keytool。
- Python 3。
- 首次构建可访问 dependencies.lock.json 中的 Maven Central 与 Google Maven 地址。

构建使用 ECJ、D8 和 aapt2，无需 Android Studio 或 Gradle。每个下载文件都有固定 SHA-256；缓存位于 .build，不提交到 Git。

## 未签名构建

```sh
python3 build.py
python3 validation/run_tests.py
python3 validation/privacy_check.py
```

产物：dist/AudioFxAuto_v64-unsigned.apk。未签名 APK 不能直接安装。仓库 downloads 中的预编译 APK 是单独签名的发布文件。

## 使用自己的签名

发布密钥和密码不在仓库。创建只属于自己的本地密钥，使用通用证书名称，不填个人邮箱、地址或真名：

```sh
mkdir -p "$HOME/.local/share/audiofx-auto"
keytool -genkeypair -alias audiofx -storetype PKCS12 -keystore "$HOME/.local/share/audiofx-auto/local-signing.p12" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AudioFx Auto Local"
```

按提示设置密码。之后在 Bash 中输入密码并构建：

```sh
read -rsp "Keystore password: " AUDIOFX_STORE_PASSWORD
printf '\n'
export AUDIOFX_STORE_PASSWORD
python3 build.py --keystore "$HOME/.local/share/audiofx-auto/local-signing.p12"
unset AUDIOFX_STORE_PASSWORD
```

产物：dist/AudioFxAuto_v64.apk。默认别名 audiofx；已有密钥的别名不同，可设置 AUDIOFX_KEY_ALIAS。也可用 AUDIOFX_KEYSTORE 指定密钥路径。

自己的签名通常不能覆盖仓库预编译 APK 或原测试版。持续升级必须保持同一密钥；请自行妥善保管，不能因为构建需要就提交密钥。

## 验证边界

validation/run_tests.py 是 JVM 测试，包括策略、数据格式、读回协调、页面稳定判断和触摸坐标数学；不模拟 Android 系统 UI、真实蓝牙、DSP 或熄屏生命周期。

validation/privacy_check.py 检查待发布文件，阻止日志、备份、私钥、未批准的地址字面量与敏感文件混入。它是辅助检查，不能证明任何修改都绝对不含隐私；发版仍需审阅变更和 APK。

不要将 .build、dist、原始交接包、反编译 APK/DEX、个人真机日志加入版本库。
