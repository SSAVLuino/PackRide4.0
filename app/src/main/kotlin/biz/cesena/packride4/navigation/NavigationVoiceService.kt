package biz.cesena.packride4.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import biz.cesena.packride4.data.prefs.UserPreferences
import biz.cesena.packride4.debug.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class AnnouncementPhase { FIRST, SECOND }

@Singleton
class NavigationVoiceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastAnnouncedInstruction = -1
    private var lastAnnouncedPhase: AnnouncementPhase? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.ITALIAN
                DebugLog.log("voice: TTS initialized")
            } else {
                DebugLog.log("voice: TTS init failed, status=$status")
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    fun reset() {
        lastAnnouncedInstruction = -1
        lastAnnouncedPhase = null
    }

    fun checkAnnouncement(
        instructionIndex: Int,
        instructionText: String,
        distanceToManeuver: Double,
        speedKmh: Float
    ) {
        if (!ttsReady) return
        val mode = userPreferences.voiceMode.value

        val (firstDist, secondDist) = thresholdsForSpeed(speedKmh)

        val phase = when {
            distanceToManeuver <= secondDist -> AnnouncementPhase.SECOND
            distanceToManeuver <= firstDist -> AnnouncementPhase.FIRST
            else -> return
        }

        if (instructionIndex == lastAnnouncedInstruction && phase == lastAnnouncedPhase) return

        val shouldSpeak = when (mode) {
            "both" -> true
            "first_only" -> phase == AnnouncementPhase.FIRST
            "second_only" -> phase == AnnouncementPhase.SECOND
            "none" -> false
            else -> true
        }

        if (!shouldSpeak) {
            lastAnnouncedInstruction = instructionIndex
            lastAnnouncedPhase = phase
            return
        }

        val text = when (phase) {
            AnnouncementPhase.FIRST -> "Tra ${formatDistanceVoice(distanceToManeuver)}, $instructionText"
            AnnouncementPhase.SECOND -> instructionText
        }

        DebugLog.log("voice: announce phase=$phase idx=$instructionIndex dist=${distanceToManeuver.toInt()}m speed=${speedKmh.toInt()}km/h")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_$instructionIndex$phase")
        lastAnnouncedInstruction = instructionIndex
        lastAnnouncedPhase = phase
    }

    private fun thresholdsForSpeed(speedKmh: Float): Pair<Double, Double> {
        return when {
            speedKmh <= 50f  -> 400.0 to 100.0
            speedKmh <= 90f  -> 800.0 to 200.0
            else             -> 1500.0 to 400.0
        }
    }

    private fun formatDistanceVoice(meters: Double): String {
        return when {
            meters >= 1000 -> "${(meters / 1000).toInt()} chilometri"
            meters >= 100 -> "${(meters / 100).toInt() * 100} metri"
            else -> "${meters.toInt()} metri"
        }
    }
}
