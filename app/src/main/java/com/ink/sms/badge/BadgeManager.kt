package com.ink.sms.badge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 桌面角标管理：Android 没有系统级统一角标 API，各厂商（小米/华为/三星/OPPO/vivo/索尼）
 * 各自实现，需下发各自的广播、或写入各自的 ContentProvider。
 * 这里按厂商分别下发角标数字（等于短信未读数），未匹配到的机型静默忽略（无角标但不崩溃）。
 */
object BadgeManager {
    private const val TAG = "BadgeManager"

    /** 设置桌面角标数字；count<=0 表示清除角标。 */
    fun setCount(context: Context, count: Int) {
        val pkg = context.packageName
        val cls = launcherActivityName(context, pkg) ?: run {
            Log.w(TAG, "无法解析 launcher Activity，跳过角标更新")
            return
        }
        // 各厂商各自容错：任一抛异常不影响其余（例如 ColorOS 写系统角标 Provider 需系统权限，会抛 SecurityException）
        runCatching { applyXiaomi(context, pkg, cls, count) }
        runCatching { applySamsung(context, pkg, cls, count) }
        runCatching { applyHuawei(context, pkg, cls, count) }
        runCatching { applyOppo(context, pkg, count) }
        runCatching { applyVivo(context, pkg, cls, count) }
        runCatching { applySony(context, pkg, cls, count) }
    }

    private fun launcherActivityName(context: Context, pkg: String): String? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className

    // 小米 MIUI：随通知条数或显式广播设置角标数字
    private fun applyXiaomi(context: Context, pkg: String, cls: String, count: Int) {
        val intent = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE")
        intent.putExtra("android.intent.extra.update_application_component_name", "$pkg/$cls")
        intent.putExtra(
            "android.intent.extra.update_application_message_text",
            if (count > 0) count.toString() else ""
        )
        context.sendBroadcast(intent)
    }

    // 三星
    private fun applySamsung(context: Context, pkg: String, cls: String, count: Int) {
        val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
        intent.putExtra("badge_count", count)
        intent.putExtra("badge_package", pkg)
        intent.putExtra("badge_class", cls)
        context.sendBroadcast(intent)
    }

    // 华为（Emotion UI / HarmonyOS 桌面）
    private fun applyHuawei(context: Context, pkg: String, cls: String, count: Int) {
        val intent = Intent("com.huawei.android.launcher.action.UPDATE_SHORTCUT_BADGE")
        intent.putExtra("packageName", pkg)
        intent.putExtra("className", cls)
        intent.putExtra("badge_add_count", 0)
        intent.putExtra("badge_count", count)
        context.sendBroadcast(intent)
    }

    // OPPO / 一加 / realme（ColorOS）：写入系统角标 Provider（按包名整包计角标）
    private fun applyOppo(context: Context, pkg: String, count: Int) {
        // ColorOS 16 角标 Provider 真实列名：app_package_name / app_badge_count（无 className 列）
        val cv = ContentValues().apply {
            put("app_package_name", pkg)
            put("app_badge_count", count)
            put("user_id", 0)
            put("calling_package_name", pkg)
        }
        try {
            context.contentResolver.insert(Uri.parse("content://com.android.badge/badge"), cv)
        } catch (e: Exception) {
            Log.w(TAG, "OPPO 角标 Provider 写入失败（可能未声明权限）", e)
        }
        // 旧版 ColorOS 仍走广播（字段名 pakeageName 为 OPPO 拼写错误）
        val intent = Intent("com.oppo.unsettledevent")
        intent.putExtra("pakeageName", pkg)
        intent.putExtra("number", count)
        intent.putExtra("upgradeNumber", count)
        context.sendBroadcast(intent)
    }

    // vivo（Funtouch / OriginOS）
    private fun applyVivo(context: Context, pkg: String, cls: String, count: Int) {
        val intent = Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM")
        intent.putExtra("packageName", pkg)
        intent.putExtra("className", cls)
        intent.putExtra("notificationNum", count)
        intent.putExtra("info", "")
        context.sendBroadcast(intent)
    }

    // 索尼
    private fun applySony(context: Context, pkg: String, cls: String, count: Int) {
        val intent = Intent("com.sonyericsson.home.action.UPDATE_BADGE")
        intent.putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", pkg)
        intent.putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", cls)
        intent.putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", count.toString())
        intent.putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
        context.sendBroadcast(intent)
    }
}
