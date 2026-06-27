package com.workez365.smsbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 설정값을 SharedPreferences로 관리하는 싱글턴 헬퍼
 */
object AppSettings {

    private const val PREF_NAME           = "sms_bridge_prefs"
    private const val KEY_ROLE            = "role"
    private const val KEY_DEVICE_ID       = "device_id"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    const val ROLE_SLAVE  = "slave"
    const val ROLE_MASTER = "master"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getRole(context: Context): String =
        prefs(context).getString(KEY_ROLE, ROLE_SLAVE) ?: ROLE_SLAVE

    fun setRole(context: Context, role: String) =
        prefs(context).edit().putString(KEY_ROLE, role).apply()

    // [fix-9] 역할 판별 헬퍼 — 분산된 getRole() == ROLE_SLAVE 비교를 단일화
    fun isSlaveMode(context: Context) = getRole(context) == ROLE_SLAVE

    fun getDeviceId(context: Context): String =
        prefs(context).getString(KEY_DEVICE_ID, "") ?: ""

    fun setDeviceId(context: Context, id: String) =
        prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()

    // [fix-8] 기기 ID + 역할이 모두 설정됐는지 한 곳에서 확인
    fun isConfigured(context: Context): Boolean = getDeviceId(context).isNotBlank()

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
}
