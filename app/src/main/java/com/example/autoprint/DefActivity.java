package com.example.autoprint;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class DefActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "autoprint_prefs";
    public static final String KEY_PRINTER_IP = "printer_ip";
    public static final String KEY_PRINTER_PORT = "printer_port";
    public static final String KEY_SAVE_GALLERY = "save_gallery";
    public static final String KEY_SHUTTER_SOUND = "shutter_sound";
    public static final String KEY_SHOW_GRID = "show_grid";

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private EditText editIp;
    private EditText editPort;
    private TextView txtConnectionStatus;
    private SwitchCompat switchSaveGallery;
    private SwitchCompat switchShutterSound;
    private SwitchCompat switchGrid;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_def);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ImageButton btnBack = findViewById(R.id.btnBack);
        editIp = findViewById(R.id.editIp);
        editPort = findViewById(R.id.editPort);
        txtConnectionStatus = findViewById(R.id.txtConnectionStatus);
        Button btnTestConnection = findViewById(R.id.btnTestConnection);
        Button btnSave = findViewById(R.id.btnSave);
        switchSaveGallery = findViewById(R.id.switchSaveGallery);
        switchShutterSound = findViewById(R.id.switchShutterSound);
        switchGrid = findViewById(R.id.switchGrid);
        TextView txtVersion = findViewById(R.id.txtVersion);

        txtVersion.setText(getAppVersionName());

        loadSettings();

        txtConnectionStatus.setText("IP deste telemóvel: " + getDeviceIpAddress());
        txtConnectionStatus.setTextColor(0xFF8A8A8E);

        btnBack.setOnClickListener(v -> finish());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSave.setOnClickListener(v -> saveSettings());
    }

    // ================= CARREGAR / GUARDAR =================

    private void loadSettings() {
        editIp.setText(prefs.getString(KEY_PRINTER_IP, "192.168.1.107"));

        // CORREÇÃO: Padrão alterado para 631 (IPP)
        int savedPort = prefs.getInt(KEY_PRINTER_PORT, 631);
        editPort.setText(String.valueOf(savedPort));

        switchSaveGallery.setChecked(prefs.getBoolean(KEY_SAVE_GALLERY, true));
        switchShutterSound.setChecked(prefs.getBoolean(KEY_SHUTTER_SOUND, true));
        switchGrid.setChecked(prefs.getBoolean(KEY_SHOW_GRID, false));
    }

    private void saveSettings() {
        String ip = editIp.getText().toString().trim();
        String portText = editPort.getText().toString().trim();

        if (TextUtils.isEmpty(ip) || !IP_PATTERN.matcher(ip).matches()) {
            editIp.setError("Endereço IP inválido");
            editIp.requestFocus();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            editPort.setError("Porta inválida (1-65535)");
            editPort.requestFocus();
            return;
        }

        prefs.edit()
                .putString(KEY_PRINTER_IP, ip)
                .putInt(KEY_PRINTER_PORT, port)
                .putBoolean(KEY_SAVE_GALLERY, switchSaveGallery.isChecked())
                .putBoolean(KEY_SHUTTER_SOUND, switchShutterSound.isChecked())
                .putBoolean(KEY_SHOW_GRID, switchGrid.isChecked())
                .apply();

        Toast.makeText(this, "Definições guardadas", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ================= TESTAR LIGAÇÃO =================

    private void testConnection() {
        String ip = editIp.getText().toString().trim();
        String portText = editPort.getText().toString().trim();

        if (TextUtils.isEmpty(ip) || !IP_PATTERN.matcher(ip).matches() || TextUtils.isEmpty(portText)) {
            txtConnectionStatus.setText("Preenche o IP e a porta antes de testar");
            txtConnectionStatus.setTextColor(0xFFD32F2F);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            txtConnectionStatus.setText("Porta inválida");
            txtConnectionStatus.setTextColor(0xFFD32F2F);
            return;
        }

        txtConnectionStatus.setText("A testar ligação à HP LaserJet...");
        txtConnectionStatus.setTextColor(0xFF8A8A8E);

        final int finalPort = port;
        new Thread(() -> {
            String errorDetail = null;
            boolean reachable = false;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, finalPort), 5000);
                reachable = true;
            } catch (java.net.SocketTimeoutException e) {
                errorDetail = "Tempo esgotado — confirma se o telemóvel e a HP M140w estão no mesmo Wi-Fi";
            } catch (java.net.ConnectException e) {
                errorDetail = "Ligação recusada na porta " + finalPort + " (tenta a porta 631 ou 9100)";
            } catch (java.net.UnknownHostException e) {
                errorDetail = "IP " + ip + " não encontrado na rede";
            } catch (IOException e) {
                errorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            boolean finalReachable = reachable;
            String finalErrorDetail = errorDetail;
            runOnUiThread(() -> {
                if (finalReachable) {
                    txtConnectionStatus.setText("Ligação com a impressora bem-sucedida (Porta " + finalPort + ")");
                    txtConnectionStatus.setTextColor(0xFF2E7D32);
                } else {
                    txtConnectionStatus.setText("Falha: " + finalErrorDetail);
                    txtConnectionStatus.setTextColor(0xFFD32F2F);
                }
            });
        }).start();
    }

    // CORREÇÃO: Método universal para obter o IP do Wi-Fi sem falhar no Android 10+
    private String getDeviceIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.getName().contains("wlan")) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && IP_PATTERN.matcher(sAddr).matches()) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return "Desconhecido / Wi-Fi desligado";
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }
}