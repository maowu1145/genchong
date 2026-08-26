package com.maowu.gravitypet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Transparent, one-time bridge used to request microphone permission and make
 * Android consider continuous overlay recording a user-initiated action.
 */
public class VoiceChatActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 51;
    private boolean dispatched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0f);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startOverlayConversation();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MICROPHONE);
        }
    }

    private void startOverlayConversation() {
        if (dispatched) return;
        dispatched = true;
        Intent intent = new Intent(this, OverlayPetService.class);
        intent.setAction(OverlayPetService.ACTION_START_CONVERSATION);
        startService(intent);
        // Keep this transparent activity foreground for a moment so Android 14+
        // grants the already-running overlay service microphone eligibility.
        new Handler(Looper.getMainLooper()).postDelayed(this::finishWithoutAnimation, 350L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startOverlayConversation();
        } else {
            Toast.makeText(this, "需要麦克风权限才能对话",
                    Toast.LENGTH_SHORT).show();
            finishWithoutAnimation();
        }
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }
}
