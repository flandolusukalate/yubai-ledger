# 构建与运行

生产技术栈：Java 17、Android Activity + 本地 WebView ES modules、SQLite；不依赖云端 AI 完成基础账务。

环境：JDK 17 或 21，Android SDK Platform 36 / Build Tools 36.0.0，Gradle Wrapper 8.13，Android Gradle Plugin 8.13.2；Node.js 20+。首次解析依赖需要网络。

1. Android Studio 打开仓库，或设置 JAVA_HOME / ANDROID_HOME。
2. 按本机路径创建 local.properties（sdk.dir），不要提交。
3. `npm ci` 后执行 `npm test`，运行共享账务模块的测试。
4. `./gradlew :app:assembleDebug :app:lintDebug`；Windows 使用 `gradlew.bat`。
5. APK 位于 app/build/outputs/apk/debug/app-debug.apk。

`npm run preview` 启动浏览器预览。浏览器不能执行原生通知监听、无障碍截图或 ML Kit OCR。

原生测试：在专用测试设备或模拟器执行，先阅读所选 Runner；不要把个人账本用作测试夹具。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.yubai.ledger.test/cn.yubai.ledger.VerificationRunner
```

指定其他 Runner 时加 `-PledgerTestRunner=cn.yubai.ledger.<RunnerName>` 重新构建测试 APK。instrument 命令返回成功不等于所有检查通过，需核对 Runner 输出。

发布 APK 为既有验证产物，使用调试签名。自行构建会使用你本机调试签名，通常不能覆盖别人的调试 APK。发布正式版之前需确定持久保管的签名、版本升级与数据迁移方案。仓库不包含任何签名私钥。

核心：app/src/main/assets/web/core.mjs；查重证据：payment-evidence.mjs；原生采集：PageCaptureService / PageWindowOcr；存储：Store；后台处理：AutoLedgerProcessor；云端：DeepSeekFallback。构建使用仓库实际锁定的依赖版本。
