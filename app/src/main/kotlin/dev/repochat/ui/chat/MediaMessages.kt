package dev.repochat.ui.chat

import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repochat.core.model.ChatMessage
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cards for AI-generated media messages (images and speech audio). Media is
 * stored as base64 on the message row; the cards decode it lazily and offer
 * a permissionless save (MediaStore on Q+, app directory below).
 */
@Composable
fun GeneratedImageCard(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(message.id, message.base64Content) { message.base64Content?.decodeImage() }
    var savedNote by remember(message.id) { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(18.dp))
            .bubbleIn(key = "img-${message.id}"),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Generated image",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = message.text,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                else -> Text(
                    text = "Image unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            message.text?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            savedNote = MediaSaver.saveImage(context, message.base64Content.orEmpty())
                        }
                    },
                    enabled = message.base64Content != null,
                ) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                savedNote?.let { note ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun GeneratedAudioCard(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember(message.id) { mutableStateOf(false) }
    var prepared by remember(message.id) { mutableStateOf(false) }
    var savedNote by remember(message.id) { mutableStateOf<String?>(null) }

    val player = remember(message.id) { MediaPlayer() }
    DisposableEffect(message.id) {
        onDispose {
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(18.dp))
            .bubbleIn(key = "audio-${message.id}"),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        if (playing) {
                            runCatching { player.pause() }
                            playing = false
                        } else {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (!prepared) {
                                        val file = MediaSaver.writeCacheAudio(
                                            context,
                                            message.base64Content.orEmpty(),
                                            "audio-${message.id}.mp3",
                                        )
                                        player.reset()
                                        player.setDataSource(file.absolutePath)
                                        player.prepare()
                                        prepared = true
                                    }
                                    player.start()
                                    true
                                }.getOrDefault(false)
                            }
                            playing = ok
                        }
                    }
                }) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = message.text.orEmpty().ifBlank { "Audio" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    scope.launch {
                        savedNote = MediaSaver.saveAudio(context, message.base64Content.orEmpty())
                    }
                }) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Save audio",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            savedNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private fun String.decodeImage(): ImageBitmap? = runCatching {
    val bytes = Base64.decode(this, Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** Permission-less media persistence (MediaStore on Q+, app dir below). */
object MediaSaver {

    suspend fun saveImage(context: android.content.Context, base64: String): String =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }
                .getOrElse { return@withContext "Could not decode image" }
            val name = "ai-cloud-${System.currentTimeMillis()}.png"
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AiCloud")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                    ) ?: return@withContext "Could not save image"
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    "Saved to Pictures/AiCloud"
                } else {
                    val dir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "AiCloud",
                    )
                    dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    "Saved to ${dir.name}/$name"
                }
            }.getOrElse { "Save failed: ${it.message ?: "error"}" }
        }

    suspend fun saveAudio(context: android.content.Context, base64: String): String =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }
                .getOrElse { return@withContext "Could not decode audio" }
            val name = "ai-cloud-${System.currentTimeMillis()}.mp3"
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AiCloud")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values,
                    ) ?: return@withContext "Could not save audio"
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    "Saved to Music/AiCloud"
                } else {
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AiCloud")
                    dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    "Saved to ${dir.name}/$name"
                }
            }.getOrElse { "Save failed: ${it.message ?: "error"}" }
        }

    fun writeCacheAudio(context: android.content.Context, base64: String, fileName: String): File {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val file = File(context.cacheDir, fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }
}
