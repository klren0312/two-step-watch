---
status: accepted
---

# 不依赖 GMS 的平台 GNSS 与熄屏优先跟踪

定位使用 Android `LocationManager` 的 GNSS 提供者，方向使用 `SensorManager` 的旋转矢量与磁力计，不使用 Google Play Services、FusedLocationProvider 或任何地图 SDK；导航由 `location` 类型的前台服务、部分唤醒锁与 OngoingActivity 支撑，默认熄屏，亮屏时 1 秒、熄屏时 5 秒采样，Ambient 显示简化界面。这样国行或无 GMS 的手表也能运行，离线行为确定；代价是冷启动搜星可能更慢，且没有融合定位的省电优化。首次定位界面必须展示卫星数与精度，并允许用户在 30 秒后选择仍然开始。
