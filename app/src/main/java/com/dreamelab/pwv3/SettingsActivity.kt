package com.dreamelab.pwv3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast

class SerialManager(private val ctx: Context) : ContextWrapper(ctx) {

    companion object {
        private const val TAG = "SerialManager"
        private var instance: SerialManager? = null

        @JvmStatic
        fun init(context: Context) {
            if (instance == null) {
                instance = SerialManager(context.applicationContext)
                Log.d(TAG, "SerialManager initialized")
            } else {
                Log.d(TAG, "SerialManager.init called but instance already exists")
            }
        }

        /**
         * SettingsActivity 等から呼ばれる静的メソッド。
         * 実際の USB 検出・パーミッション要求・ポートオープンは
         * instance?.findAndRequestPermissionAndOpenImpl(activity) を実装すること。
         */
        @JvmStatic
        fun findAndRequestPermissionAndOpen(activity: Activity) {
            try {
                if (instance == null) {
                    init(activity)
                }
                instance?.findAndRequestPermissionAndOpenImpl(activity) ?: run {
                    Log.w(TAG, "findAndRequestPermissionAndOpen: instance is null")
                    Toast.makeText(activity, "SerialManager not available", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "findAndRequestPermissionAndOpen failed: ${e.message}", e)
                Toast.makeText(activity, "Serial permission request failed: ${e.localizedMessage ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }

        /**
         * デバッグ用にメソッド存在チェックのログを出すヘルパー
         */
        @JvmStatic
        fun logSerialManagerInfo() {
            try {
                val cls = SerialManager::class.java
                val hasRegister = try { cls.getMethod("registerReceivers"); true } catch (_: NoSuchMethodException) { false }
                val hasUnregister = try { cls.getMethod("unregisterReceivers"); true } catch (_: NoSuchMethodException) { false }
                Log.d(TAG, "SerialManager methods: registerReceivers=$hasRegister unregisterReceivers=$hasUnregister")
            } catch (e: Exception) {
                Log.e(TAG, "logSerialManagerInfo failed: ${e.message}", e)
            }
        }
    }

    // --- 以下はインスタンス側の最小スタブ実装 ---
    fun findAndRequestPermissionAndOpenImpl(activity: Activity) {
        // TODO: 実際の USB 検出・パーミッション要求・ポートオープン処理をここに実装する
        Log.d(TAG, "findAndRequestPermissionAndOpenImpl called")
        Toast.makeText(activity, "SerialManager: findAndRequestPermissionAndOpen called (stub)", Toast.LENGTH_SHORT).show()
    }

    // レシーバ登録/解除のスタブ（必要なら具体実装を追加）
    fun registerReceivers() {
        Log.d(TAG, "registerReceivers stub")
    }

    fun unregisterReceivers() {
        Log.d(TAG, "unregisterReceivers stub")
    }
}
