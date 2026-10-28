package com.klkl.tracknav.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import com.klkl.tracknav.MainActivity
import com.klkl.tracknav.R
import com.klkl.tracknav.TrackNavApplication

/**
 * 导航前台服务：保住进程不被回收，并提供常驻通知与“正在导航”表盘活动卡片。
 *
 * 本服务不持有导航状态，只负责通知/前台优先级，并通过屏幕开关广播通知
 * [NavigationSessionManager] 切换低功耗模式（熄屏后降频定位、关罗盘）。
 */
class NavigationForegroundService : Service() {

    /** 熄屏→低功耗，亮屏→恢复正常频率。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lowPower = intent?.action == Intent.ACTION_SCREEN_OFF
            (application as? TrackNavApplication)?.navigationManager
                ?.setLowPowerMode(lowPower)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "轨迹导航",
            // 导航通知不能响铃/弹窗，否则会频繁打断运动中的用户。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "导航进行中"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重复启动（如系统重启服务）时 intent 可能为 null，需兜底名称。
        val routeName = intent?.getStringExtra(EXTRA_ROUTE_NAME)?.takeIf { it.isNotBlank() }
            ?: "轨迹导航"
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("轨迹导航")
            .setContentText(routeName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val foregroundStarted = runCatching {
            startForeground(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                // 声明 location 类型，满足 Android 14+ 前台服务类型要求。
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to enter foreground: ${error.message}")
        }.isSuccess
        if (!foregroundStarted) {
            // 进入前台失败（如后台启动限制）时通知界面降级为“仅前台导航”。
            (application as? TrackNavApplication)?.navigationManager?.onForegroundServiceFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        // OngoingActivity 让表盘在亮屏时展示导航入口；不支持的设备忽略。
        runCatching {
            OngoingActivity.Builder(this, NOTIFICATION_ID, notificationBuilder)
                .setStaticIcon(R.drawable.ic_stat_nav)
                .setTitle("轨迹导航")
                .setContentDescription(routeName)
                .setTouchIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .build()
        }.onFailure { error ->
            Log.w(TAG, "OngoingActivity unavailable: ${error.message}")
        }

        val powerManager = getSystemService(PowerManager::class.java)
        // 服务可能在熄屏状态下被拉起，初始就对齐低功耗状态。
        (application as? TrackNavApplication)?.navigationManager
            ?.setLowPowerMode(powerManager?.isInteractive == false)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ROUTE_NAME = "route_name"

        /** 启动前台服务；被系统限制时返回 false，由调用方决定是否降级。 */
        fun start(context: Context, routeName: String): Boolean {
            val intent = Intent(context, NavigationForegroundService::class.java)
                .putExtra(EXTRA_ROUTE_NAME, routeName)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { error ->
                Log.w(TAG, "Unable to start navigation service: ${error.message}")
            }.isSuccess
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, NavigationForegroundService::class.java))
            }
        }
    }
}
