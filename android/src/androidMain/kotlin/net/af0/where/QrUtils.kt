package net.af0.where

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import net.af0.where.e2ee.QrPayload

private const val TAG = "QrUtils"

object QrUtils {
    fun payloadToUrl(qr: QrPayload): String = qr.toUrl()

    fun urlToPayload(url: String): QrPayload? = QrPayload.fromUrl(url)

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
