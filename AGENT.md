# AGENT.md

本项目是 Galaxy Watch4 Classic（Wear OS 3+）上的个人离线 GPX 轨迹导航应用。
产品与领域决策以 [README.md](README.md)、[GLOSSARY.md](GLOSSARY.md)、[docs/adr](docs/adr/) 为准。

## Git 工作流（重要）

- 只有用户明确要求“提交代码/commit”时才创建提交；不要自行 commit。
- **提交后自动推送**：每次 commit 完成后立即执行 `git push` 到 `origin` 的当前分支（当前为 `main`），不要再等用户单独说 “push”。
- push 失败时报告真实错误并停止；禁止 force push、禁止修改 git config、禁止跳过 hook。
- tag 仍是单独动作；除非用户明确要求，提交时不自动打 tag。
- 提交前检查 `git status`，不要提交 `keystore/`、`keystore.properties`、`local.properties`、`build/`、导出的 APK 或抓包/临时文件。
- 提交信息使用中文 conventional commit：简短 subject + 变更与验证说明。

## 产品边界

- 手表端单 APK，无手机伴侣；导航期间不发起任何网络请求。
- 导入有两条通道：
  - 网页导入：应用内临时监听局域网 HTTP（随机 token、20 MB 上限、只接受 `.gpx`、上传后自动入库、离开页面即停服）。
  - adb：推送 GPX 到 app-specific `import/` 目录后手动扫描。
- `INTERNET` 权限仅用于本地监听；禁止添加外联请求、统计、云同步、在线地图或第三方定位/地图 SDK。
- 无底图；Canvas 自绘轨迹。定位用 `LocationManager` GNSS，方向用 `SensorManager`，不依赖 GMS。
- 记录实际轨迹并导出 GPX 到 app-specific `exports/`；不接健康平台，不上传两步路。
- 界面与项目文档使用中文；用户要求把模拟器截图放进 README。

## 常用命令

```bash
# 测试与构建
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

- JDK 17；Android SDK：`D:\Apps\androidsdk`。
- adb：`D:\Apps\androidsdk\platform-tools\adb.exe`。
- Wear OS 4 AVD：`TrackNavWear`（454×454）。无窗口启动：
  ```bash
  D:\Apps\androidsdk\emulator\emulator.exe -avd TrackNavWear -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
  ```
- 安装：`adb install -r app/build/outputs/apk/release/app-release.apk`；包名 `com.klkl.tracknav`。
- release 签名使用本地 `keystore.properties` + `keystore/`（均已 gitignore，不得提交或输出密钥）。
- 路线推送：`tools\push-route.bat <file.gpx>`；记录拉取：`tools\pull-records.bat`。
- 网页导入验证可用 `adb forward tcp:8765 tcp:8765` 后直接请求页面。

## 关键位置

- `app/src/main/java/com/klkl/tracknav/web/WebImportServer.kt`：局域网上传服务与 token。
- `app/src/main/java/com/klkl/tracknav/ui/WebImportScreen.kt`：二维码/地址与停服生命周期。
- `app/src/main/java/com/klkl/tracknav/navigation/`：导航引擎、路线匹配、定位/罗盘、记录、前台服务。
- `app/src/main/java/com/klkl/tracknav/ui/`：路线库、详情、导航三页、总结、设置。
- `docs/screenshots/`：README 使用的模拟器与真机截图。
- `docs/adr/`：已确认的架构决策。

## 当前状态

- v0.2.0；37 个单元测试通过；已在 Wear OS 4 模拟器验证。
- 真机 SM-R8950（Galaxy Watch4 Classic 46mm，国行 wiseulcn，Android 16 / SDK 36，450×450，含 GMS）已安装签名 v0.2.0；定位/通知/后台定位已授权。
- 真机网页导入已用 PC 同热点验证：146 m 测试轨迹与 17.3 km 牛王寨蝴蝶线导入成功；离开页面服务停止。
- 待实测：1 km / 17 km 的 GPS/罗盘/表圈/Ambient/前台通知/续航；手机浏览器上传路径待验。
- 进程死亡后的活动导航恢复尚未实现；已走轨迹会恢复导出。
