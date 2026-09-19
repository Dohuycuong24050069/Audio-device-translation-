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
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TranslationEngine - Xử lý dịch thuật thời gian thực siêu tốc & chuẩn xác
 *
 * Hai cấp độ dịch:
 * 1. translateFast(): Dịch tức thì On-Device (<15ms, 0ms mạng) bằng Google ML Kit khi người dùng đang nói.
 * 2. translate(): Dịch chính xác cao bằng Google Neural Engine (GTX) khi hoàn thành câu.
 */
class TranslationEngine(private val context: Context) {

    private val TAG = "TranslationEngine"
    private var enToViTranslator: Translator? = null
    private var viToEnTranslator: Translator? = null
    private var isEnToViReady = false
    private var isViToEnReady = false

    // Cache LRU (128 entry) — 0ms lag cho các cụm từ lặp lại
    private val translationCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 120
    }

    init {
        initTranslators()
    }

    private fun initTranslators() {
        try {
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

            val conditions = DownloadConditions.Builder().build()

            enToViTranslator?.downloadModelIfNeeded(conditions)
                ?.addOnSuccessListener {
                    isEnToViReady = true
                    Log.d(TAG, "Mô hình Offline EN -> VI đã sẵn sàng")
                }

            viToEnTranslator?.downloadModelIfNeeded(conditions)
                ?.addOnSuccessListener {
                    isViToEnReady = true
                    Log.d(TAG, "Mô hình Offline VI -> EN đã sẵn sàng")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo ML Kit: ${e.message}")
        }
    }

    private fun normalizeInputText(text: String): String {
        val trimmed = text.trim().replace("\\s+".toRegex(), " ")
        if (trimmed.isEmpty()) return ""
        return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Dịch siêu tốc On-Device (<15ms, không tốn thời gian mạng)
     * Dùng cho hiển thị thời gian thực song song với lúc âm thanh đang phát.
     */
    suspend fun translateFast(text: String, sourceLang: String, targetLang: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeInputText(text)
        if (normalized.isBlank() || normalized.length < 2) return@withContext ""

        val src = if (sourceLang.lowercase().startsWith("vi")) "vi" else "en"
        val tgt = if (targetLang.lowercase().startsWith("en")) "en" else "vi"

        val cacheKey = "${src}>${tgt}:${normalized}"
        synchronized(translationCache) {
            translationCache[cacheKey]?.let { return@withContext it }
        }

        val isEnToVi = src == "en"
        val translator = if (isEnToVi) enToViTranslator else viToEnTranslator
        val isReady = if (isEnToVi) isEnToViReady else isViToEnReady

        if (isReady && translator != null) {
            try {
                val result = translator.translate(normalized).await()
                if (result.isNotBlank()) {
                    synchronized(translationCache) { translationCache[cacheKey] = result }
                    return@withContext result
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return@withContext ""
    }

    /**
     * Dịch chính xác cao (khi hoàn tất câu hoặc dừng nói)
     * Ưu tiên Google Neural GTX -> Fallback ML Kit On-Device
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String, preferCloud: Boolean = true): String = withContext(Dispatchers.IO) {
        val normalized = normalizeInputText(text)
        if (normalized.isBlank() || normalized.length < 2) return@withContext ""

        val src = if (sourceLang.lowercase().startsWith("vi")) "vi" else "en"
        val tgt = if (targetLang.lowercase().startsWith("en")) "en" else "vi"

        val cacheKey = "${src}>${tgt}:${normalized}"
        synchronized(translationCache) {
            translationCache[cacheKey]?.let { return@withContext it }
        }

        // 1. Google Neural GTX (chuẩn xác cao, văn phong tự nhiên)
        if (preferCloud) {
            val cloudResult = translateViaGoogleGTX(normalized, src, tgt)
            if (!cloudResult.isNullOrBlank()) {
                synchronized(translationCache) { translationCache[cacheKey] = cloudResult }
                return@withContext cloudResult
            }
        }

        // 2. Fallback On-Device ML Kit
        val isEnToVi = src == "en"
        val translator = if (isEnToVi) enToViTranslator else viToEnTranslator
        val isReady = if (isEnToVi) isEnToViReady else isViToEnReady

        if (isReady && translator != null) {
            try {
                val localResult = translator.translate(normalized).await()
                if (localResult.isNotBlank()) {
                    synchronized(translationCache) { translationCache[cacheKey] = localResult }
                    return@withContext localResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit fallback lỗi: ${e.message}")
            }
        }

        return@withContext ""
    }

    private fun translateViaGoogleGTX(text: String, src: String, tgt: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=$tgt&dt=t&q=$encoded"
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val jsonArray = JSONArray(responseText)
                val outerList = jsonArray.optJSONArray(0)
                if (outerList != null && outerList.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until outerList.length()) {
                        val part = outerList.optJSONArray(i)
                        if (part != null) {
                            val translatedPart = part.optString(0, "")
                            if (translatedPart.isNotEmpty()) {
                                sb.append(translatedPart)
                            }
                        }
                    }
                    val finalResult = sb.toString().trim()
                    if (finalResult.isNotEmpty()) {
                        return finalResult
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        try {
            enToViTranslator?.close()
            viToEnTranslator?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi close: ${e.message}")
        }
    }
}
