package com.vanta.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

/** Google consent flow for the user's Gmail and Calendar data. */
class GoogleWorkspaceAuthActivity : ComponentActivity() {
    private val requestCode=4107
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val request=AuthorizationRequest.builder().setRequestedScopes(listOf(
            Scope("https://www.googleapis.com/auth/gmail.modify"),
            Scope("https://www.googleapis.com/auth/calendar.events")
        )).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if(result.hasResolution()) {
                    try { startIntentSenderForResult(result.pendingIntent!!.intentSender,requestCode,null,0,0,0) }
                    catch(e:Exception) { fail("Could not open Google consent: "+(e.message?:"unknown error")) }
                } else saveResult(result)
            }
            .addOnFailureListener { e -> fail("Google authorization failed: "+(e.message?:"unknown error")) }
    }
    @Deprecated("AuthorizationClient result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=this.requestCode) return
        if(resultCode!=Activity.RESULT_OK||data==null) { fail("Google account connection was cancelled."); return }
        try { saveResult(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)) }
        catch(e:Exception) { fail("Could not finish Google authorization: "+(e.message?:"unknown error")) }
    }
    private fun saveResult(result:AuthorizationResult) {
        val token=result.accessToken.orEmpty()
        if(token.isBlank()) { fail("Google returned no access token. Try connecting again."); return }
        if(!runCatching { VantaStore(this).saveGoogleAccessToken(token) }.getOrDefault(false)) {
            fail("Google connected, but Vanta could not save the token securely."); return
        }
        Toast.makeText(this,"Google Workspace connected to Vanta",Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK); finish()
    }
    private fun fail(message:String) {
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_CANCELED); finish()
    }
}
