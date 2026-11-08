package net.alummahnow.core.bridge.auth

/** One registered native client (auth migration `0013_native_clients`). Public client: no secret. */
data class OAuthConfig(
    val issuer: String,
    val clientId: String,
    val redirectUri: String? = null,
    val scopes: List<String> = listOf("openid", "profile", "email", "offline_access")
) {
    val authorizeEndpoint get() = "$issuer/authorize"
    val tokenEndpoint get() = "$issuer/token"
    val deviceEndpoint get() = "$issuer/device_authorization"
}
