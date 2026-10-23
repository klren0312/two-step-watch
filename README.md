# 轨迹导航

Galaxy Watch4 Classic（Wear OS 3+）上的离线 GPX 轨迹导航应用。手表端单 APK，不依赖手机伴侣，不申请网络权限。

<p align="center">
  <img src="docs/screenshots/home.png" width="220" alt="路线库" />
  <img src="docs/screenshots/detail.png" width="220" alt="路线详情" />
  <img src="docs/screenshots/navigation.png" width="220" alt="离线导航" />
</p>

> 截图来自 Wear OS 4 模拟器（454×454），定位使用模拟 GPS；真机验收待完成。

## 功能

- **两步路 GPX 导入**：`<trk>` 多段、`<rte>` 回退、CDATA/emoji、只保留命名标注点、20 MB 上限、SHA-256 去重。
- **离线导航**：纯轨迹地图、朝向朝上/北向朝上、表圈缩放、全程概览、已走/未走分色。
- **偏航提醒**：阈值 30/50/100 m、持续判定、震动、回归点方位、回到轨迹自动解除。
- **标注点**：30 m 到达提醒；贴线倒退时提示“方向可能相反”。
- **记录与导出**：自动记录实走 GPX；进程中断后残留记录在下次启动时自动恢复导出。
- **后台与续航**：前台服务 + OngoingActivity + 部分唤醒锁；亮屏 1 s、熄屏 5 s 采样；Ambient 简化界面。
- **设置**：偏航阈值、震动、默认朝向、导航常亮，全部持久化。
- **零网络**：无 INTERNET 权限，无统计、云同步或第三方地图。

## 快速开始

### 构建

需要 JDK 17 与 Android SDK（本项目在 `D:\Apps\androidsdk` 下验证）。

```bash
./gradlew testDebugUnitTest
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

release 签名读取本地 `keystore.properties` 与 `keystore/`（已 gitignore）。这两个文件不随仓库分发；缺失时只能生成未签名 release 或使用 debug 包。

### 安装到手表

手表开启开发者选项与无线调试后：

```bash
adb pair <IP:配对端口> <配对码>
adb connect <IP:调试端口>
adb install -r app/build/outputs/apk/release/app-release.apk
```

手表端独立运行，无需手机 App。

### 导入路线

```bash
tools\push-route.bat D:\path\route.gpx
```

然后手表打开「轨迹导航」→「导入新轨迹」。也可手动推送：

```bash
adb push route.gpx /sdcard/Android/data/com.klkl.tracknav/files/import/
```

同内容文件只会导入一次；删除路线后可重新导入。

### 导出记录

```bash
tools\pull-records.bat
```

默认拉取到桌面 `tracknav-exports`。手表内导出目录：

```text
/sdcard/Android/data/com.klkl.tracknav/files/exports/
```

## 文档

- [领域词汇](GLOSSARY.md)
- [ADR-0001：手表端单 APK、adb 导入、零网络](docs/adr/0001-watch-only-offline-adb-import.md)
- [ADR-0002：v1 不提供底图](docs/adr/0002-no-basemap-v1.md)
- [ADR-0003：无 GMS 平台 GNSS 与熄屏跟踪](docs/adr/0003-gms-free-gnss-screen-off.md)

## 状态与限制

- 版本 `0.1.0`；35 个单元测试通过；已在 Wear OS 4 模拟器验证导入、导航、后台记录与导出。
- 真机 GPS 冷启动、罗盘、表圈、Ambient 切换与整段续航验收待完成。
- 进程被杀后的“继续导航”尚未实现；已走轨迹会恢复导出。
- 自接近的回头弯路段存在 GPS 几何歧义，偏航判定需真机路线复验。
