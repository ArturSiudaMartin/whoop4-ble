package dev.whoop4.bloop.app

/**
 * Activity types a session can have. [key] is stored in activity_session.type; [strava] is Strava's sport_type
 * (Workout where Strava has no match). Sessions from before 0.4.1 have type "workout" and show as "Other".
 */
data class ActivityType(val key: String, val label: String, val emoji: String, val strava: String)

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
        ActivityType("strength", "Strength training", "🏋️", "WeightTraining"),
        ActivityType("functional", "Functional fitness", "🤸", "Crossfit"),
        ActivityType("hiit", "HIIT", "🔥", "HighIntensityIntervalTraining"),
        ActivityType("yoga", "Yoga", "🧘", "Yoga"),
        ActivityType("pilates", "Pilates", "🧘", "Pilates"),
        ActivityType("elliptical", "Elliptical", "⚙️", "Elliptical"),
        ActivityType("stairmaster", "Stairmaster", "🪜", "StairStepper"),
        ActivityType("climbing", "Rock climbing", "🧗", "RockClimbing"),
        ActivityType("boxing", "Boxing", "🥊", "Workout"),
        ActivityType("martial_arts", "Martial arts", "🥋", "Workout"),
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
}
