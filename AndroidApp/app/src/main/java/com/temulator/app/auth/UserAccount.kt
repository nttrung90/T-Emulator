package com.temulator.app.auth

data class UserAccount(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?
)

data class UserSession(
    val uid: String?,
    val generation: Long,
    val user: UserAccount?
)

typealias AuthSession = UserSession

sealed interface AuthState {
    object Unauthenticated : AuthState
    object Loading : AuthState
    data class Authenticated(val user: UserAccount) : AuthState
    data class Error(val message: String) : AuthState
}
