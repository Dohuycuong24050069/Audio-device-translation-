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
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        val translator = if (sourceLang.lowercase().startsWith("en")) {
            enToViTranslator
        } else {
            viToEnTranslator
        }

        return@withContext try {
            translator?.translate(text)?.await() ?: "[Chưa tải xong mô hình dịch]"
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi dịch: ${e.message}")
            "[Lỗi dịch: ${e.localizedMessage}]"
        }
    }

    fun close() {
        enToViTranslator?.close()
        viToEnTranslator?.close()
    }
}
