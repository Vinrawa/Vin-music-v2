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
        val defaultWebClientId = if (resId != 0) {
            try {
                context.getString(resId)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        
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
        authState = AuthState.Authenticating
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        
        viewModelScope.launch {
            try {
                val result = auth.signInWithCredential(credential).await()
                if (result.user != null) {
                    Log.d(TAG, "Successfully authenticated with Firebase: ${result.user?.email}")
                    authState = AuthState.Authenticated
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

    /**
     * Trigger manual cloud backup.
     */
    fun backupDataToCloud() {
        if (currentUser == null) {
            syncState = SyncState.Error("User not signed in")
            return
        }
        syncState = SyncState.Syncing
        lastSyncMessage = "Backing up liked songs and playlists..."
        com.vinmusic.analytics.AnalyticsHelper.logCloudBackupInitiated(getApplication())
        
        viewModelScope.launch {
            val result = syncManager.backupLocalDataToCloud()
            if (result.isSuccess) {
                syncState = SyncState.Success
                lastSyncMessage = "Successfully backed up data!"
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
        if (currentUser == null) {
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
                lastSyncMessage = "Data successfully restored and merged!"
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
