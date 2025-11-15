package com.safevoice.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.safevoice.app.EmergencyPopupActivity;
import com.safevoice.app.R;

import java.util.Map;

public class FirebaseAlertService extends FirebaseMessagingService {

    private static final String TAG = "FirebaseAlertService";
    private static final String EMERGENCY_CHANNEL_ID = "EmergencyAlertChannel";

    private static Ringtone ringtone;

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "FCM Message Received from: " + remoteMessage.getFrom());

        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            Log.d(TAG, "Message data payload: " + data);
            String alertType = data.get("type");

            if ("emergency".equals(alertType)) {
                handleEmergencyAlert(data);
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "Refreshed FCM token: " + token);
        // Here you would send the token to your server to associate it with the user
    }

    private void handleEmergencyAlert(Map<String, String> data) {
        String callerName = data.get("callerName");
        String callerUid = data.get("callerUid");
        String sessionId = data.get("sessionId"); // WebRTC session ID
        String location = data.get("location");

        // Wake up the device
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.ON_AFTER_RELEASE,
                    "SafeVoice:EmergencyWakeLock"
            );
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
            wakeLock.release();
        }

        // Start playing a loud siren/alarm sound
        playAlarmSound();

        // Show the full-screen emergency pop-up
        Intent popupIntent = new Intent(this, EmergencyPopupActivity.class);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_NAME, callerName);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_UID, callerUid);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_SESSION_ID, sessionId);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_LOCATION, location);
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startActivity(popupIntent);

        // Also show a high-priority notification as a fallback
        showEmergencyNotification(callerName);
    }

    private void showEmergencyNotification(String callerName) {
        Intent intent = new Intent(this, EmergencyPopupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
                    "Emergency Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("High-priority alerts for Safe Voice emergencies");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EMERGENCY ALERT")
                .setContentText(callerName + " has triggered a Safe Voice alert!")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true) // Makes it a heads-up notification
                .setContentIntent(pendingIntent);

        notificationManager.notify(1, notificationBuilder.build());
    }



    private void playAlarmSound() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
            }
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), alarmSound);
            ringtone.play();
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }
    }

    public static void stopAlarmSound() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }
}