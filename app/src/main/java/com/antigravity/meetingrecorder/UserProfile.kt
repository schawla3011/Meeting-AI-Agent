package com.antigravity.meetingrecorder

/**
 * User profile data class — stored in Firestore under users/{uid}.
 */
data class UserProfile(
    val uid:         String = "",
    val name:        String = "",
    val email:       String = "",
    val company:     String = "",
    val industry:    String = "",
    val designation: String = "",
)
