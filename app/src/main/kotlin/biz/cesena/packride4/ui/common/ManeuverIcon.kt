package biz.cesena.packride4.ui.common

import androidx.annotation.DrawableRes
import biz.cesena.packride4.R

/**
 * Maps a GraphHopper instruction sign (or OSRM type/modifier) to the corresponding
 * direction drawable from the MapLibre Navigation icon set.
 *
 * GraphHopper signs:
 *  -7 keep left   -3 sharp left  -2 left  -1 slight left
 *   0 straight
 *   1 slight right  2 right  3 sharp right  7 keep right
 *   4 finish/arrive   6 roundabout
 */
@DrawableRes
fun maneuverIcon(sign: Int, modifier: String = "", exitNumber: Int = 0): Int = when (sign) {
    -3   -> R.drawable.direction_turn_sharp_left
    -2   -> R.drawable.direction_turn_left
    -1   -> R.drawable.direction_turn_slight_left
    0    -> R.drawable.direction_continue_straight
    1    -> R.drawable.direction_turn_slight_right
    2    -> R.drawable.direction_turn_right
    3    -> R.drawable.direction_turn_sharp_right
    4    -> R.drawable.direction_arrive
    6    -> roundaboutIcon(modifier, exitNumber)
    -7   -> R.drawable.direction_fork_slight_left
    7    -> R.drawable.direction_fork_slight_right
    else -> R.drawable.direction_continue_straight
}

/**
 * Picks the roundabout directional icon. When exitNumber is known (1-8),
 * maps to the closest directional variant based on typical roundabout geometry
 * (right-hand traffic, counterclockwise rotation in Italy):
 *   1 = slight right, 2 = right, 3 = sharp right / straight,
 *   4 = straight (U), 5 = sharp left, 6 = left, 7 = slight left, 8+ = straight
 */
@DrawableRes
fun roundaboutIcon(modifier: String = "", exitNumber: Int = 0): Int {
    // If the routing engine already gave us a directional modifier, use it
    val fromModifier = when (modifier) {
        "sharp left"   -> R.drawable.direction_roundabout_sharp_left
        "left"         -> R.drawable.direction_roundabout_left
        "slight left"  -> R.drawable.direction_roundabout_slight_left
        "straight"     -> R.drawable.direction_roundabout_straight
        "slight right" -> R.drawable.direction_roundabout_slight_right
        "right"        -> R.drawable.direction_roundabout_right
        "sharp right"  -> R.drawable.direction_roundabout_sharp_right
        else           -> null
    }
    if (fromModifier != null) return fromModifier

    // Fall back to exit number (right-hand traffic, counterclockwise roundabout)
    return when (exitNumber) {
        1    -> R.drawable.direction_roundabout_slight_right
        2    -> R.drawable.direction_roundabout_right
        3    -> R.drawable.direction_roundabout_sharp_right
        4    -> R.drawable.direction_roundabout_straight   // ~180° = straight through
        5    -> R.drawable.direction_roundabout_sharp_left
        6    -> R.drawable.direction_roundabout_left
        7    -> R.drawable.direction_roundabout_slight_left
        else -> R.drawable.direction_roundabout
    }
}

/** Convenience overload for OSRM type+modifier strings. */
@DrawableRes
fun maneuverIconOsrm(type: String, modifier: String): Int = when (type) {
    "depart"         -> R.drawable.direction_depart
    "arrive"         -> R.drawable.direction_arrive
    "roundabout", "rotary" -> when (modifier) {
        "sharp left"  -> R.drawable.direction_roundabout_sharp_left
        "left"        -> R.drawable.direction_roundabout_left
        "slight left" -> R.drawable.direction_roundabout_slight_left
        "straight"    -> R.drawable.direction_roundabout_straight
        "slight right"-> R.drawable.direction_roundabout_slight_right
        "right"       -> R.drawable.direction_roundabout_right
        "sharp right" -> R.drawable.direction_roundabout_sharp_right
        else          -> R.drawable.direction_roundabout
    }
    "fork"           -> when (modifier) {
        "left", "slight left"  -> R.drawable.direction_fork_slight_left
        "right", "slight right"-> R.drawable.direction_fork_slight_right
        else                   -> R.drawable.direction_fork_straight
    }
    "merge"          -> when (modifier) {
        "left", "slight left"  -> R.drawable.direction_merge_slight_left
        "right", "slight right"-> R.drawable.direction_merge_slight_right
        else                   -> R.drawable.direction_merge_straight
    }
    "off ramp"       -> when (modifier) {
        "left", "slight left"  -> R.drawable.direction_off_ramp_slight_left
        "right", "slight right"-> R.drawable.direction_off_ramp_slight_right
        else                   -> R.drawable.direction_off_ramp_right
    }
    "on ramp"        -> when (modifier) {
        "left", "slight left"  -> R.drawable.direction_on_ramp_slight_left
        "right", "slight right"-> R.drawable.direction_on_ramp_slight_right
        else                   -> R.drawable.direction_on_ramp_right
    }
    "end of road"    -> when (modifier) {
        "left"  -> R.drawable.direction_end_of_road_left
        else    -> R.drawable.direction_end_of_road_right
    }
    "use lane", "continue" -> R.drawable.direction_continue_straight
    else /* turn */  -> when (modifier) {
        "sharp left"  -> R.drawable.direction_turn_sharp_left
        "left"        -> R.drawable.direction_turn_left
        "slight left" -> R.drawable.direction_turn_slight_left
        "uturn"       -> R.drawable.direction_uturn
        "slight right"-> R.drawable.direction_turn_slight_right
        "right"       -> R.drawable.direction_turn_right
        "sharp right" -> R.drawable.direction_turn_sharp_right
        else          -> R.drawable.direction_continue_straight
    }
}
