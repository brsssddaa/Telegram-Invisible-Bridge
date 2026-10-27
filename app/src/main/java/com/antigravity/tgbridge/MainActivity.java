package com.antigravity.tgbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusTextView;
    private TextView infoTextView;
    private Switch toggleSwitch;
    private Button connectTgButton;
    private Button batteryOptButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.statusTextView);
        infoTextView = findViewById(R.id.infoTextView);
        toggleSwitch = findViewById(R.id.toggleSwitch);
        connectTgButton = findViewById(R.id.connectTgButton);
        batteryOptButton = findViewById(R.id.batteryOptButton);

        updateUI();

        toggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Intent serviceIntent = new Intent(this, BridgeService.class);
            if (isChecked) {
                serviceIntent.setAction(BridgeService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                serviceIntent.setAction(BridgeService.ACTION_STOP);
                startService(serviceIntent);
            }
            toggleSwitch.postDelayed(this::updateUI, 300);
        });

        connectTgButton.setOnClickListener(v -> {
            try {
                // Прямой вызов Telegram с параметрами локального SOCKS5 моста
                String tgUrl = "tg://socks?server=127.0.0.1&port=10808";
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(tgUrl));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Telegram не найден на устройстве", Toast.LENGTH_LONG).show();
            }
        });

        batteryOptButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "Фоновая работа уже разрешена!", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Откройте настройки батареи вручную", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        boolean running = BridgeService.isEngineRunning();
        toggleSwitch.setChecked(running);

        if (running) {
            statusTextView.setText("● МОСТ АКТИВЕН (127.0.0.1:10808)");
            statusTextView.setTextColor(0xFF4CAF50); // Зеленый
            infoTextView.setText("Telegram защищен от блокировок ТСПУ.\nБанковские приложения работают напрямую без ограничений.");
            connectTgButton.setEnabled(true);
        } else {
            statusTextView.setText("○ МОСТ ОСТАНОВЛЕН");
            statusTextView.setTextColor(0xFFF44336); // Красный
            infoTextView.setText("Включите переключатель выше, чтобы запустить локальный мост.");
            connectTgButton.setEnabled(false);
        }
    }
}
