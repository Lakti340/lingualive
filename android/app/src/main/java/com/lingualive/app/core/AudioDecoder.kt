package com.lingualive.app.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Décode la piste audio d'un fichier audio/vidéo en PCM 16 bits mono (rééchantillonné si demandé). */
object AudioDecoder {

    fun open(ctx: Context, uri: Uri): Pair<MediaExtractor, MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ctx, uri, null)
        } catch (e: Exception) {
            extractor.release(); error("Fichier illisible")
        }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("Aucune piste audio dans ce fichier") }
        extractor.selectTrack(track)
        return extractor to extractor.getTrackFormat(track)
    }

    /**
     * @param targetRate fréquence voulue (ex. 16000 pour Vosk), ou null pour garder celle du fichier.
     * @param write reçoit des blocs PCM 16 bits little-endian mono.
     */
    fun decode(extractor: MediaExtractor, format: MediaFormat, targetRate: Int?, write: (ByteArray) -> Unit) {
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    out.position(info.offset); out.limit(info.offset + info.size)
                    val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val mono = ShortArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (ch in 0 until channels) sum += shorts.get(f * channels + ch)
                        mono[f] = (sum / channels).toShort()
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    val pcm = if (targetRate != null && targetRate != rate) resample(mono, rate, targetRate) else mono
                    if (pcm.isNotEmpty()) write(toBytes(pcm))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = pos - i0
            out[i] = (input[i0] * (1 - frac) + input[i1] * frac).toInt().toShort()
        }
        return out
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            b[i * 2] = (v and 0xFF).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }
}
