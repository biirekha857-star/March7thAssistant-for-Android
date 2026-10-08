package com.miguanm7a.hsr.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台保活相关的系统设置引导。
 *
 * 前台服务 + WakeLock 已经能挡住大部分系统回收，但国产 ROM（小米/华为/OPPO/vivo）
 * 还有自己的「后台管理」：即便有前台服务，也可能被「省电策略」或「自启动管理」
 * 掐掉。这些没有 API 可以绕过，只能引导用户手动放行 —— 至少要拿到
 * **电池优化白名单**，这是 AOSP 层面就有明确接口的一项。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /** 是否已加入电池优化白名单（即「不优化」）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 申请加入电池优化白名单。
     *
     * 优先弹系统自带的「是否允许后台运行」对话框；部分 ROM 不支持该 action，
     * 就退化为打开电池优化设置列表让用户自己找。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(context, direct)) return

        launch(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 打开本应用的「应用详情」页，方便用户关掉厂商的后台限制。 */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!launch(context, intent)) {
            launch(
                context,
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "无法打开设置页：${intent.action}", t)
        false
    }
}
