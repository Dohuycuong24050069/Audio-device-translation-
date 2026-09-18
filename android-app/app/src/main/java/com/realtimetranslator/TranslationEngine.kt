package com.realtimetranslator

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * TranslationEngine - Xử lý dịch thuật thời gian thực
 * Sử dụng Google ML Kit On-Device Translation:
 * - Hoàn toàn miễn phí, không giới hạn request
 * - Tốc độ cực nhanh (on-device), độ trễ thấp (< 50ms)
 * - Hỗ trợ dịch 2 chiều: English <-> Vietnamese
 */
class TranslationEngine(private val context: Context) {

    private val TAG = "TranslationEngine"
    private var enToViTranslator: Translator? = null
    private var viToEnTranslator: Translator? = null
    private var isEnToViReady = false
    private var isViToEnReady = false

    init {
        initTranslators()
    }

    private fun initTranslators() {
        val enToViOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        enToViTranslator = Translation.getClient(enToViOptions)

        val viToEnOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.VIETNAMESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        viToEnTranslator = Translation.getClient(viToEnOptions)

        val conditions = DownloadConditions.Builder()
            .build()

        enToViTranslator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                isEnToViReady = true
                Log.d(TAG, "Mô hình EN -> VI đã sẵn sàng")
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Lỗi tải mô hình EN -> VI: ${e.message}")
            }

        viToEnTranslator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                isViToEnReady = true
                Log.d(TAG, "Mô hình VI -> EN đã sẵn sàng")
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Lỗi tải mô hình VI -> EN: ${e.message}")
            }
    }

    /**
     * Dịch chuỗi văn bản theo hướng ngôn ngữ chỉ định
     * Ưu tiên On-Device ML Kit (offline, siêu nhanh < 30ms).
     * Tự động fallback sang Cloud API nếu mô hình máy học đang tải ngầm lần đầu!
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext ""

        val isEnToVi = sourceLang.lowercase().startsWith("en")
        val translator = if (isEnToVi) enToViTranslator else viToEnTranslator
        val isReady = if (isEnToVi) isEnToViReady else isViToEnReady

        // 1. Nếu ML Kit on-device đã sẵn sàng, dịch trực tiếp offline
        if (isReady && translator != null) {
            try {
                return@withContext translator.translate(trimmed).await()
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit dịch lỗi, chuyển sang fallback: ${e.message}")
            }
        }

        // 2. Fallback sang Cloud Translation (MyMemory API) ngay tức khắc trong khi mô hình đang tải
        try {
            val src = if (isEnToVi) "en" else "vi"
            val tgt = if (isEnToVi) "vi" else "en"
            val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$src|$tgt"
            val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(responseText)
                if (json.optInt("responseStatus") == 200) {
                    val resData = json.optJSONObject("responseData")
                    val result = resData?.optString("translatedText") ?: ""
                    if (result.isNotBlank()) return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback dịch API lỗi: ${e.message}")
        }

        // 3. Nếu cả 2 đều chưa được (rất hiếm khi máy không có mạng và model chưa tải xong)
        try {
            return@withContext translator?.translate(trimmed)?.await() ?: "Đang tải bộ dịch..."
        } catch (e: Exception) {
            return@withContext "Đang cập nhật mô hình dịch..."
        }
    }

    fun close() {
        enToViTranslator?.close()
        viToEnTranslator?.close()
    }
}
