package com.kazemail.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class DefaultMailAppStatus(
    val isDefaultAppEmail: Boolean = false,
    val isDefaultMailto: Boolean = false,
    val isEmailRoleAvailable: Boolean = false,
    val isEmailRoleHeld: Boolean = false,
    val currentMailAppPackage: String? = null,
    val currentMailAppLabel: String? = null,
    val currentMailtoPackage: String? = null,
    val currentMailtoLabel: String? = null,
) {
    val isPrimaryDefault: Boolean
        get() = isEmailRoleHeld || (isDefaultAppEmail && isDefaultMailto)

    val isPartiallyDefault: Boolean
        get() = !isPrimaryDefault && (isDefaultAppEmail || isDefaultMailto)
}

/**
 * 端末上の「デフォルトのメールアプリ」(CATEGORY_APP_EMAIL / mailto: / ROLE_EMAIL) の状態検出と
 * OS設定画面への遷移インテント組み立てを行う。
 */
object DefaultMailAppHelper {

    private const val ROLE_EMAIL = "android.app.role.EMAIL"

    fun inspectStatus(context: Context): DefaultMailAppStatus {
        val pm = context.packageManager
        val selfPkg = context.packageName

        val (roleAvailable, roleHeld) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (rm != null) {
                val available = runCatching { rm.isRoleAvailable(ROLE_EMAIL) }.getOrDefault(false)
                val held = available && runCatching { rm.isRoleHeld(ROLE_EMAIL) }.getOrDefault(false)
                available to held
            } else {
                false to false
            }
        } else {
            false to false
        }

        val appEmailIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
        }
        val mailtoIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))

        val appEmailResolved = resolveDefaultHandler(pm, appEmailIntent, selfPkg)
        val mailtoResolved = resolveDefaultHandler(pm, mailtoIntent, selfPkg)

        return DefaultMailAppStatus(
            isDefaultAppEmail = appEmailResolved.isSelf,
            isDefaultMailto = mailtoResolved.isSelf,
            isEmailRoleAvailable = roleAvailable,
            isEmailRoleHeld = roleHeld,
            currentMailAppPackage = appEmailResolved.packageName,
            currentMailAppLabel = appEmailResolved.label,
            currentMailtoPackage = mailtoResolved.packageName,
            currentMailtoLabel = mailtoResolved.label,
        )
    }

    fun buildRoleRequestIntentOrNull(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        val available = runCatching { rm.isRoleAvailable(ROLE_EMAIL) }.getOrDefault(false)
        val held = available && runCatching { rm.isRoleHeld(ROLE_EMAIL) }.getOrDefault(false)
        if (!available || held) return null
        return runCatching { rm.createRequestRoleIntent(ROLE_EMAIL) }.getOrNull()
    }

    fun buildManageDefaultAppsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
            if (findActivity(context) == null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun buildAppDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            if (findActivity(context) == null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun openDefaultMailAppSettings(context: Context): Boolean {
        val defaultAppsIntent = buildManageDefaultAppsIntent(context)
        val startedDefaultApps = runCatching {
            context.startActivity(defaultAppsIntent)
            true
        }.getOrDefault(false)
        if (startedDefaultApps) return true

        val detailsIntent = buildAppDetailsIntent(context)
        return runCatching {
            context.startActivity(detailsIntent)
            true
        }.getOrDefault(false)
    }

    internal data class ResolvedHandler(
        val isSelf: Boolean,
        val packageName: String?,
        val label: String?,
    )

    internal fun classifyResolvedHandler(
        packageName: String?,
        activityName: String?,
        label: String?,
        selfPackageName: String,
    ): ResolvedHandler {
        if (packageName.isNullOrBlank()) {
            return ResolvedHandler(isSelf = false, packageName = null, label = null)
        }
        if (packageName == selfPackageName) {
            return ResolvedHandler(isSelf = true, packageName = packageName, label = label)
        }
        if (
            packageName == "android" ||
            packageName == "com.android.intentresolver" ||
            activityName.orEmpty().endsWith("ResolverActivity")
        ) {
            // OS disambiguation chooser — no default mail app chosen yet
            return ResolvedHandler(isSelf = false, packageName = null, label = null)
        }
        return ResolvedHandler(isSelf = false, packageName = packageName, label = label)
    }

    private fun resolveDefaultHandler(
        pm: PackageManager,
        intent: Intent,
        selfPackageName: String,
    ): ResolvedHandler {
        val info: ResolveInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull()

        val actInfo = info?.activityInfo
        val pkg = actInfo?.packageName
        val actName = actInfo?.name
        val label = info?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
        return classifyResolvedHandler(pkg, actName, label, selfPackageName)
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
