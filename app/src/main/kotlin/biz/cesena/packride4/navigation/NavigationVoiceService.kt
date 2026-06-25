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

        val t = thresholdsForSpeed(speedKmh)

        val phase = when {
            distanceToManeuver <= t.secondTrigger -> AnnouncementPhase.SECOND
            distanceToManeuver <= t.firstTrigger -> AnnouncementPhase.FIRST
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
            AnnouncementPhase.FIRST -> "Tra ${t.firstLabel}, $instructionText"
            AnnouncementPhase.SECOND -> instructionText
        }

        DebugLog.log("voice: announce phase=$phase idx=$instructionIndex dist=${distanceToManeuver.toInt()}m speed=${speedKmh.toInt()}km/h")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_$instructionIndex$phase")
        lastAnnouncedInstruction = instructionIndex
        lastAnnouncedPhase = phase
    }

    private data class VoiceThresholds(
        val firstTrigger: Double,
        val firstLabel: String,
        val secondTrigger: Double,
    )

    private fun thresholdsForSpeed(speedKmh: Float): VoiceThresholds {
        // Trigger distances include a TTS offset (~3s of travel) so the spoken
        // round distance matches reality by the time the phrase finishes.
        //   ≤40 km/h → ~33m offset, say "400 metri", second at 100m
        //   ≤90 km/h → ~60m offset, say "800 metri", second at 200m
        //   >90 km/h → ~110m offset, say "1 chilometro e mezzo", second at 400m
        return when {
            speedKmh <= 40f  -> VoiceThresholds(440.0, "400 metri", 130.0)
            speedKmh <= 90f  -> VoiceThresholds(860.0, "800 metri", 260.0)
            else             -> VoiceThresholds(1610.0, "1 chilometro e mezzo", 510.0)
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
