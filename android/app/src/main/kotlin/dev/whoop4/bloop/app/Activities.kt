package dev.whoop4.bloop.app

/**
 * Activity types a session can have. [key] is stored in activity_session.type; [strava] is Strava's sport_type
 * (Workout where Strava has no match). Sessions from before 0.4.1 have type "workout" and show as "Other".
 * [muscular] types add muscular load from wrist motion to strain (see Strain v4), since heart rate misses most of
 * the effort of lifting. [icon] names a line icon in ui.ActivityIcons.
 */
data class ActivityType(val key: String, val label: String, val icon: String, val strava: String, val muscular: Boolean = false)

object Activities {
    val all: List<ActivityType> = listOf(
        ActivityType("running", "Running", "run", "Run"),
        ActivityType("trail_running", "Trail running", "run", "TrailRun"),
        ActivityType("treadmill", "Treadmill", "run", "VirtualRun"),
        ActivityType("walking", "Walking", "walk", "Walk"),
        ActivityType("hiking", "Hiking", "hike", "Hike"),
        ActivityType("cycling", "Cycling", "bike", "Ride"),
        ActivityType("mountain_biking", "Mountain biking", "bike", "MountainBikeRide"),
        ActivityType("spin", "Indoor cycling", "bike", "VirtualRide"),
        ActivityType("rowing", "Rowing", "row", "Rowing"),
        ActivityType("indoor_rowing", "Indoor rowing", "row", "VirtualRow"),
        ActivityType("swimming", "Swimming", "swim", "Swim"),
        ActivityType("strength", "Strength training", "dumbbell", "WeightTraining", muscular = true),
        ActivityType("functional", "Functional fitness", "kettlebell", "Crossfit", muscular = true),
        ActivityType("hiit", "HIIT", "flame", "HighIntensityIntervalTraining", muscular = true),
        ActivityType("yoga", "Yoga", "yoga", "Yoga"),
        ActivityType("pilates", "Pilates", "yoga", "Pilates"),
        ActivityType("elliptical", "Elliptical", "elliptical", "Elliptical"),
        ActivityType("stairmaster", "Stairmaster", "stairs", "StairStepper"),
        ActivityType("climbing", "Rock climbing", "carabiner", "RockClimbing", muscular = true),
        ActivityType("boxing", "Boxing", "glove", "Workout", muscular = true),
        ActivityType("martial_arts", "Martial arts", "kick", "Workout", muscular = true),
        ActivityType("football", "Football (soccer)", "football", "Soccer"),
        ActivityType("basketball", "Basketball", "basketball", "Workout"),
        ActivityType("tennis", "Tennis", "racket", "Tennis"),
        ActivityType("padel", "Padel", "racket", "Padel"),
        ActivityType("squash", "Squash", "racket", "Squash"),
        ActivityType("badminton", "Badminton", "shuttlecock", "Badminton"),
        ActivityType("table_tennis", "Table tennis", "racket", "TableTennis"),
        ActivityType("volleyball", "Volleyball", "volleyball", "Workout"),
        ActivityType("golf", "Golf", "flag", "Golf"),
        ActivityType("skiing", "Skiing", "snow", "AlpineSki"),
        ActivityType("cross_country_skiing", "Cross-country skiing", "snow", "NordicSki"),
        ActivityType("snowboarding", "Snowboarding", "snow", "Snowboard"),
        ActivityType("ice_skating", "Ice skating", "skate", "IceSkate"),
        ActivityType("inline_skating", "Inline skating", "rollerskate", "InlineSkate"),
        ActivityType("skateboarding", "Skateboarding", "skateboard", "Skateboard"),
        ActivityType("surfing", "Surfing", "surfboard", "Surfing"),
        ActivityType("kayaking", "Kayaking", "kayak", "Kayaking"),
        ActivityType("paddleboarding", "Paddleboarding", "surfboard", "StandUpPaddling"),
        ActivityType("dance", "Dance", "note", "Workout"),
        ActivityType("stretching", "Stretching", "stretch", "Workout"),
        ActivityType("other", "Other", "heart", "Workout"),
    )

    private val byKey = all.associateBy { it.key }

    fun of(key: String?): ActivityType = byKey[key] ?: byKey.getValue("other")

    /** (start, end) of the [sessions] whose type counts muscular load; a running session ends at [now]. */
    fun muscularWindows(sessions: List<dev.whoop4.bloop.data.ActivitySessionEntity>, now: Long): List<Pair<Long, Long>> =
        sessions.filter { of(it.type).muscular }.map { it.startUtcMs to (it.endUtcMs ?: now) }
}
