# Credential Manager finds its Play services provider by reflection (androidx.credentials docs).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
