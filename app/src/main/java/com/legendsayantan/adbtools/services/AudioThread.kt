package com.legendsayantan.adbtools.services

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.BUF_SIZE
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.CHANNEL
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.LOG_TAG
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.SAMPLE_RATE
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.bandDivision
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.notiUpdateTime
import com.legendsayantan.adbtools.services.SoundMasterService.Companion.zeroByte

/**
 * @author legendsayantan
 */
class AudioThread(
    val context: Context,
    val pkg: String,
    private val mediaProjection: MediaProjection
) : Thread("$LOG_TAG : $pkg") {
    var playback = true
    var volume: Float = 1f
    var targetVolume: Float = 100f
    val dataBuffer = ByteArray(BUF_SIZE)
    lateinit var monitorThread : Thread
    var loadedCycles = 0
    var latencyUpdate:(Int)->Unit = {}
    private var stereoGainFactor = arrayOf(1f, 1f)
    private var bandCompensations = arrayOf(0, 0, 0)

    val equalizer by lazy { Equalizer(0, mTrack.audioSessionId) }
    val enhancer by lazy { LoudnessEnhancer(mTrack.audioSessionId) }
    val suppress by lazy { NoiseSuppressor.create(mTrack.audioSessionId) }
    val echoCancel by lazy { AcousticEchoCanceler.create(mTrack.audioSessionId) }

    lateinit var mRecord: AudioRecord
    lateinit var mTrack: AudioTrack
    var savedBands = arrayOf(50f, 50f, 50f)
    override fun start() {
        ShizukuRunner.runAdbCommand("appops set $pkg PLAY_AUDIO deny",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context,"Shizuku Error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        super.start()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun run() {
        if (ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            interrupt()
            return
        }
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
//                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) //causes failure
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                .addMatchingUid(Utils.getAppUidFromPackage(context, pkg))
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            mRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                )
                .setAudioPlaybackCaptureConfig(config)
                .build()

            mTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL,
                AudioFormat.ENCODING_PCM_16BIT, BUF_SIZE,
                AudioTrack.MODE_STREAM
            )

            mTrack.playbackRate = SAMPLE_RATE

            setCurrentVolume(targetVolume)
        } catch (e: Exception) {
            Log.e(
                "Error",
                "Initializing Audio Record and Play objects Failed ${e.message} for $pkg"
            )
        }
        try {
            mRecord.startRecording()
            Log.i(LOG_TAG, "Audio Recording started")
            mTrack.play()
            Log.i(LOG_TAG, "Audio Playing started")
            try {
                equalizer.enabled = true
            } catch (e: Exception) {
                Log.i(LOG_TAG, "EQ NOT SUPPORTED")
            }
            while (playback) {
                mRecord.read(dataBuffer, 0, BUF_SIZE)
                mTrack.write(dataBuffer, 0, dataBuffer.size)
                loadedCycles++
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in VolumeThread: ${e.message}")
        }
    }

    fun setCurrentVolume(it: Float) {
        volume = (it / 100f).coerceAtMost(1f)
        mTrack.setStereoVolume(volume * stereoGainFactor[0], volume * stereoGainFactor[1])
        try {
            enhancer.enabled = it > 100
            if (it > 100) enhancer.setTargetGain(((it.toInt() - 100) * 150))
        } catch (e: Exception) {
            Log.i(LOG_TAG, "ENHANCER NOT SUPPORTED")
        }
        try {
            suppress.enabled = it > 100
        } catch (e: Exception) {
            Log.i(LOG_TAG, "NOISE SUPPRESSION NOT SUPPORTED")
        }
        try {
            echoCancel.enabled = it > 100
        } catch (e: Exception) {
            Log.i(LOG_TAG, "ECHO CANCELLATION NOT SUPPORTED")
        }
    }

    fun getBalance(): Float {
        return (100 - (stereoGainFactor[0] * 100).toInt()) - (100 - (stereoGainFactor[1] * 100))
    }

    fun setBalance(value: Float) {
        stereoGainFactor = arrayOf(
            if (value <= 0) 1f else 1f - (value / 100f),
            if (value >= 0) 1f else 1f + (value / 100f)
        )
        mTrack.setStereoVolume(volume * stereoGainFactor[0], volume * stereoGainFactor[1])
    }

    fun setBand(band: Int, value: Float) {
        savedBands[band] = value
        updateBandLevel(band, value)
    }

    /**
     * changes levels of certain band groups in equalizer. follows SoundMasterService.bandDivision.
     */
    private fun updateBandLevel(bandRange: Int, percentage: Float = -1f) {
        try {
            // Iterate through the frequency bands
            val modifiedLevel =
                equalizer.bandLevelRange[0] +
                        ((equalizer.bandLevelRange[1] - equalizer.bandLevelRange[0]) * percentage / 100f) +
                        bandCompensations[bandRange]
            for (i in 0 until equalizer.numberOfBands) {
                val centerFreq = equalizer.getCenterFreq(i.toShort()) / 1000
                if (centerFreq in bandDivision[bandRange]..bandDivision[bandRange + 1]) {
                    equalizer.setBandLevel(
                        i.toShort(),
                        if (percentage >= 0) modifiedLevel.toInt().toShort()
                        else (equalizer.getBandLevel(i.toShort()) + bandCompensations[bandRange]).toShort()
                            .coerceIn(equalizer.bandLevelRange[0], equalizer.bandLevelRange[1])
                    )
                }
            }
        } catch (e: Exception) {
            Log.i(LOG_TAG, "EQ NOT SUPPORTED")
        }
    }

    fun getLatency(): Float {
        return notiUpdateTime.toFloat()/loadedCycles.coerceAtLeast(1).also { loadedCycles=0 }
    }

    override fun interrupt() {
        playback = false
        ShizukuRunner.runAdbCommand("appops set $pkg PLAY_AUDIO allow",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context,"Shizuku Error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        mRecord.stop()
        mRecord.release()
        mTrack.stop()
        mTrack.release()
        try{
            monitorThread.interrupt()
        }catch (_:Exception){

        }
        super.interrupt()
    }
}