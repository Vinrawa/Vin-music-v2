package com.vinmusic.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vinmusic.data.FirebaseSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    app: Application,
    private val syncManager: FirebaseSyncManager
) : AndroidViewModel(app) {
    private val TAG = "AuthViewModel"

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Firebase updates its auth listener asynchronously.  Use the SDK's
    // source of truth for one-shot sync actions so a freshly completed Google
    // sign-in cannot race the listener and report "User not signed in".
    private fun authenticatedUser() = auth.currentUser ?: currentUser
    
    // ── Observable states for Jetpack Compose ──────────────────────────────────
    var currentUser by mutableStateOf(auth.currentUser)
        private set

    var authState by mutableStateOf<AuthState>(AuthState.Idle)
        private set

    var syncState by mutableStateOf<SyncState>(SyncState.Idle)
        private set

    var lastSyncMessage by mutableStateOf("")
        private set

    init {
        // Observe auth state changes
        auth.addAuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
            if (firebaseAuth.currentUser != null) {
                authState = AuthState.Authenticated
            } else {
                authState = AuthState.Idle
            }
        }
    }

    /**
     * Auth state enumeration.
     */
    sealed interface AuthState {
        object Idle : AuthState
        object Authenticating : AuthState
        object Authenticated : AuthState
        data class Error(val message: String) : AuthState
    }

    /**
     * Sync state enumeration.
     */
    sealed interface SyncState {
        object Idle : SyncState
        object Syncing : SyncState
        object Success : SyncState
        data class Error(val message: String) : SyncState
    }

    /**
     * Create the Google SignIn Client.
     */
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        Log.d(TAG, "getGoogleSignInClient: default_web_client_id resId=$resId")
        val defaultWebClientId = if (resId != 0) {
            try {
                context.getString(resId)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        Log.d(TAG, "getGoogleSignInClient: webClientId=${if (defaultWebClientId.isNotEmpty()) "present(${defaultWebClientId.length} chars)" else "EMPTY"}")
        
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).apply {
            if (defaultWebClientId.isNotEmpty()) {
                requestIdToken(defaultWebClientId)
            }
            requestEmail()
        }.build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun isGoogleConfigured(context: Context): Boolean {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        Log.d(TAG, "isGoogleConfigured: resId=$resId")
        if (resId == 0) return false
        return try {
            context.getString(resId).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sign in to Firebase with Google credentials.
     */
    fun signInWithGoogle(account: GoogleSignInAccount) {
        Log.d(TAG, "signInWithGoogle: email=${account.email}, hasIdToken=${!account.idToken.isNullOrBlank()}, hasServerAuthCode=${!account.serverAuthCode.isNullOrBlank()}")
        authState = AuthState.Authenticating
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            val message = "Google sign-in did not return an ID token. Check the Firebase web client ID and SHA-1 configuration."
            Log.e(TAG, message)
            authState = AuthState.Error(message)
            com.vinmusic.analytics.AnalyticsHelper.logSignInFailed(getApplication(), "google", message)
            return
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        
        viewModelScope.launch {
            try {
                val result = auth.signInWithCredential(credential).await()
                if (result.user != null) {
                    Log.d(TAG, "Successfully authenticated with Firebase: ${result.user?.email}")
                    authState = AuthState.Authenticated
                    // Persist the gate immediately as a second, deterministic
                    // signal for the Compose login overlay. The Firebase auth
                    // listener remains the source of truth for currentUser.
                    val user = result.user!!
                    getApplication<Application>()
                        .getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_logged_in", true)
                        .putString("user_name", user.displayName ?: user.email?.substringBefore("@") ?: "Google User")
                        .putString("user_email", user.email)
                        .apply()
                    com.vinmusic.analytics.AnalyticsHelper.logSignInSuccess(getApplication(), "google")
                    // Automatically trigger a restore (pull down data) on new login
                    restoreCloudData()
                } else {
                    authState = AuthState.Error("Firebase Auth user is null")
                    com.vinmusic.analytics.AnalyticsHelper.logSignInFailed(getApplication(), "google", "Firebase Auth user is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase auth signin failed: ${e.message}", e)
                authState = AuthState.Error(e.message ?: "Authentication failed")
                com.vinmusic.analytics.AnalyticsHelper.logSignInFailed(getApplication(), "google", e.message ?: "Authentication failed")
            }
        }
    }

    /** Surface activity-result failures instead of silently returning to the login screen. */
    fun reportGoogleSignInError(message: String) {
        val safeMessage = message.ifBlank { "Google sign-in was cancelled or failed." }
        Log.e(TAG, safeMessage)
        authState = AuthState.Error(safeMessage)
        com.vinmusic.analytics.AnalyticsHelper.logSignInFailed(getApplication(), "google", safeMessage)
    }

    /**
     * Trigger manual cloud backup.
     */
    fun backupDataToCloud() {
        if (authenticatedUser() == null) {
            syncState = SyncState.Error("User not signed in")
            return
        }
        syncState = SyncState.Syncing
        lastSyncMessage = "Backing up playlists, likes & downloads..."
        com.vinmusic.analytics.AnalyticsHelper.logCloudBackupInitiated(getApplication())
        
        viewModelScope.launch {
            val result = syncManager.backupLocalDataToCloud()
            if (result.isSuccess) {
                syncState = SyncState.Success
                lastSyncMessage = "Successfully backed up data to cloud!"
                com.vinmusic.analytics.AnalyticsHelper.logCloudBackupSuccess(getApplication())
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Backup failed"
                syncState = SyncState.Error(errorMsg)
                lastSyncMessage = "Backup failed: $errorMsg"
                com.vinmusic.analytics.AnalyticsHelper.logCloudBackupFailed(getApplication(), errorMsg)
            }
        }
    }

    /**
     * Trigger manual cloud restore.
     */
    fun restoreCloudData() {
        if (authenticatedUser() == null) {
            syncState = SyncState.Error("User not signed in")
            return
        }
        syncState = SyncState.Syncing
        lastSyncMessage = "Syncing from cloud..."
        com.vinmusic.analytics.AnalyticsHelper.logCloudRestoreInitiated(getApplication())
        
        viewModelScope.launch {
            val result = syncManager.restoreDataFromCloud()
            if (result.isSuccess) {
                syncState = SyncState.Success
                lastSyncMessage = "Successfully restored playlists, likes & downloads!"
                com.vinmusic.analytics.AnalyticsHelper.logCloudRestoreSuccess(getApplication())
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Restore failed"
                syncState = SyncState.Error(errorMsg)
                lastSyncMessage = "Restore failed: $errorMsg"
                com.vinmusic.analytics.AnalyticsHelper.logCloudRestoreFailed(getApplication(), errorMsg)
            }
        }
    }

    /**
     * Sign out.
     */
    fun signOut(context: Context) {
        viewModelScope.launch {
            try {
                // Sign out of Firebase
                auth.signOut()
                // Sign out of Google
                getGoogleSignInClient(context).signOut()
                authState = AuthState.Idle
                syncState = SyncState.Idle
                lastSyncMessage = "Signed out successfully."
                Log.d(TAG, "Successfully signed out.")
                com.vinmusic.analytics.AnalyticsHelper.logSignOut(context)
            } catch (e: Exception) {
                Log.e(TAG, "Signout failed: ${e.message}", e)
            }
        }
    }
}
