package net.af0.where

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.QrPayload
import java.util.Base64

object QrUtils {
    private val json = Json { ignoreUnknownKeys = true }

    fun payloadToUrl(qr: QrPayload): String {
        val encoded =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(qr).encodeToByteArray())
        return "where://invite?q=$encoded"
    }

    fun urlToPayload(url: String): QrPayload? =
        try {
            val q = url.substringAfter("?q=", "").ifEmpty { return null }
            json.decodeFromString<QrPayload>(Base64.getUrlDecoder().decode(q).decodeToString())
        } catch (_: Exception) {
            null
        }

    fun generateBitmap(
        content: String,
        size: Int = 512,
    ): Bitmap? =
        try {
            val bits =
                QRCodeWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    mapOf(EncodeHintType.MARGIN to 1),
                )
            Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bmp ->
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
}
