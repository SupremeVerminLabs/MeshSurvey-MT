package meshsurvey.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    try { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() } catch (_: Exception) { null }
