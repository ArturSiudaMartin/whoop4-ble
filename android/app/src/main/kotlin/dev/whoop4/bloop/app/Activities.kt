package dev.whoop4.bloop.app

/**
 * Activity types a session can have. [key] is stored in activity_session.type; [strava] is Strava's sport_type
 * (Workout where Strava has no match). Sessions from before 0.4.1 have type "workout" and show as "Other".
 * [muscular] types add muscular load from wrist motion to strain (see Strain v4), since heart rate misses most of
 * the effort of lifting.
 */
data class ActivityType(val key: String, val label: String, val emoji: String, val strava: String, val muscular: Boolean = false)

object Activities {
    val all: List<ActivityType> = listOf(
        ActivityType("running", "Running", "🏃", "Run"),
        ActivityType("trail_running", "Trail running", "🏃", "TrailRun"),
        ActivityType("treadmill", "Treadmill", "🏃", "VirtualRun"),
        ActivityType("walking", "Walking", "🚶", "Walk"),
        ActivityType("hiking", "Hiking", "🥾", "Hike"),
        ActivityType("cycling", "Cycling", "🚴", "Ride"),
        ActivityType("mountain_biking", "Mountain biking", "🚵", "MountainBikeRide"),
        ActivityType("spin", "Indoor cycling", "🚴", "VirtualRide"),
        ActivityType("rowing", "Rowing", "🚣", "Rowing"),
        ActivityType("indoor_rowing", "Indoor rowing", "🚣", "VirtualRow"),
        ActivityType("swimming", "Swimming", "🏊", "Swim"),
        ActivityType("strength", "Strength training", "🏋️", "WeightTraining", muscular = true),
        ActivityType("functional", "Functional fitness", "🤸", "Crossfit", muscular = true),
        ActivityType("hiit", "HIIT", "🔥", "HighIntensityIntervalTraining", muscular = true),
        ActivityType("yoga", "Yoga", "🧘", "Yoga"),
        ActivityType("pilates", "Pilates", "🧘", "Pilates"),
        ActivityType("elliptical", "Elliptical", "⚙️", "Elliptical"),
        ActivityType("stairmaster", "Stairmaster", "🪜", "StairStepper"),
        ActivityType("climbing", "Rock climbing", "🧗", "RockClimbing", muscular = true),
        ActivityType("boxing", "Boxing", "🥊", "Workout", muscular = true),
        ActivityType("martial_arts", "Martial arts", "🥋", "Workout", muscular = true),
        ActivityType("football", "Football (soccer)", "⚽", "Soccer"),
        ActivityType("basketball", "Basketball", "🏀", "Workout"),
        ActivityType("tennis", "Tennis", "🎾", "Tennis"),
        ActivityType("padel", "Padel", "🎾", "Padel"),
        ActivityType("squash", "Squash", "🎾", "Squash"),
        ActivityType("badminton", "Badminton", "🏸", "Badminton"),
        ActivityType("table_tennis", "Table tennis", "🏓", "TableTennis"),
        ActivityType("volleyball", "Volleyball", "🏐", "Workout"),
        ActivityType("golf", "Golf", "⛳", "Golf"),
        ActivityType("skiing", "Skiing", "⛷️", "AlpineSki"),
        ActivityType("cross_country_skiing", "Cross-country skiing", "⛷️", "NordicSki"),
        ActivityType("snowboarding", "Snowboarding", "🏂", "Snowboard"),
        ActivityType("ice_skating", "Ice skating", "⛸️", "IceSkate"),
        ActivityType("inline_skating", "Inline skating", "🛼", "InlineSkate"),
        ActivityType("skateboarding", "Skateboarding", "🛹", "Skateboard"),
        ActivityType("surfing", "Surfing", "🏄", "Surfing"),
        ActivityType("kayaking", "Kayaking", "🛶", "Kayaking"),
        ActivityType("paddleboarding", "Paddleboarding", "🏄", "StandUpPaddling"),
        ActivityType("dance", "Dance", "💃", "Workout"),
        ActivityType("stretching", "Stretching", "🤸", "Workout"),
        ActivityType("other", "Other", "❤️", "Workout"),
    )

    private val byKey = all.associateBy { it.key }

    fun of(key: String?): ActivityType = byKey[key] ?: byKey.getValue("other")

    /** (start, end) of the [sessions] whose type counts muscular load; a running session ends at [now]. */
    fun muscularWindows(sessions: List<dev.whoop4.bloop.data.ActivitySessionEntity>, now: Long): List<Pair<Long, Long>> =
        sessions.filter { of(it.type).muscular }.map { it.startUtcMs to (it.endUtcMs ?: now) }
}
