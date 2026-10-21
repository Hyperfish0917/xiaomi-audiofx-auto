# 第三方组件

- Shizuku API、Provider、AIDL、Shared：13.1.5，项目 https://github.com/RikkaApps/Shizuku-API ，MIT 许可证见 THIRD_PARTY_LICENSE_Shizuku.txt。
- AndroidX Annotation：1.3.0，来自 Google Maven，Apache-2.0，许可证原文见 LICENSES/Apache-2.0.txt。
- ECJ、R8/D8、aapt2、apksig、Robolectric android-all 用于编译、签名或测试。依赖版本、来源和哈希记录在 dependencies.lock.json，构建时下载，不将完整工具或 Android 平台类打进应用。
- org.json 20240303 用于 JVM 测试，不作为额外运行时依赖打入 APK。

本仓库不分发小米的 MiSound.apk、系统 DEX、反编译源码全集或原始手机日志。系统产品与商标属于相应权利人。

本项目代码暂未指定项目许可证；以上组件保留其各自许可证。
