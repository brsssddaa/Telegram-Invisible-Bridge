package com.antigravity.tgbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class BridgeService extends Service {
    public static final String ACTION_START = "com.antigravity.tgbridge.START";
    public static final String ACTION_STOP = "com.antigravity.tgbridge.STOP";
    private static final String CHANNEL_ID = "tg_bridge_channel";
    private static final int NOTIF_ID = 10808;

    private static AntiDpiEngine engine;

    public static boolean isEngineRunning() {
        return engine != null && engine.isRunning();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopBridge();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());
        startBridge();
        return START_STICKY;
    }

    private void startBridge() {
        if (engine == null || !engine.isRunning()) {
            engine = new AntiDpiEngine();
            try {
                engine.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void stopBridge() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Мост Telegram (Anti-DPI)",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Фоновый локальный сервис моста для Telegram");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("Telegram-Bridge активен")
                .setContentText("Локальный порт: 127.0.0.1:10808 (Без системного VPN)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
