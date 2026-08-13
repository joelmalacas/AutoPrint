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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.regex.Pattern;

public class DefActivity extends AppCompatActivity {

    // Nome do ficheiro de preferências e chaves usadas para guardar cada valor
    public static final String PREFS_NAME = "autoprint_prefs";
    public static final String KEY_PRINTER_IP = "printer_ip";
    public static final String KEY_PRINTER_PORT = "printer_port";
    public static final String KEY_SAVE_GALLERY = "save_gallery";
    public static final String KEY_SHUTTER_SOUND = "shutter_sound";
    public static final String KEY_SHOW_GRID = "show_grid";
    public static final String KEY_SELECTED_TEMPLATE = "selected_template_path";

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
        Button btnManageTemplates = findViewById(R.id.btnManageTemplates);
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
        btnManageTemplates.setOnClickListener(v ->
                startActivity(new android.content.Intent(DefActivity.this, TemplateActivity.class)));

        setupSaveGalleryToggle();
        setupShutterSoundToggle();
        setupGridToggle();
    }

    // Persiste imediatamente a alteração ao switch "Mostrar grelha"
    private void setupGridToggle() {
        switchGrid.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(KEY_SHOW_GRID, isChecked).apply());
    }

    // Reage imediatamente ao alternar o switch "Som do obturador": persiste logo a
    // alteração e toca o som na hora, para o utilizador ouvir o que está a ativar/desativar.
    private void setupShutterSoundToggle() {
        android.media.MediaActionSound previewSound = new android.media.MediaActionSound();
        previewSound.load(android.media.MediaActionSound.SHUTTER_CLICK);

        switchShutterSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SHUTTER_SOUND, isChecked).apply();

            if (isChecked) {
                previewSound.play(android.media.MediaActionSound.SHUTTER_CLICK);
            }
        });
    }

    // Reage imediatamente ao alternar o switch "Guardar na galeria": não é preciso
    // carregar em "Guardar definições" para isto ter efeito na próxima fotografia.
    private void setupSaveGalleryToggle() {
        switchSaveGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SAVE_GALLERY, isChecked).apply();

            if (isChecked) {
                Toast.makeText(this,
                        "As fotos vão passar a aparecer na Galeria do telemóvel",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "As fotos deixam de aparecer na Galeria — ficam guardadas só dentro da app",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // ================= CARREGAR / GUARDAR =================

    private void loadSettings() {
        editIp.setText(prefs.getString(KEY_PRINTER_IP, ""));

        int savedPort = prefs.getInt(KEY_PRINTER_PORT, 631); // 631 é a porta standard do protocolo IPP
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

        txtConnectionStatus.setText("A testar ligação...");
        txtConnectionStatus.setTextColor(0xFF8A8A8E);

        final int finalPort = port;
        // Ligação de rede tem de correr fora da thread principal
        new Thread(() -> {
            String errorDetail = null;
            boolean reachable = false;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, finalPort), 5000);
                reachable = true;
            } catch (java.net.SocketTimeoutException e) {
                errorDetail = "Sem resposta (tempo esgotado) — o telemóvel provavelmente não está na mesma rede, ou há uma firewall/router a bloquear";
            } catch (java.net.ConnectException e) {
                errorDetail = "Ligação recusada pelo dispositivo (" + e.getMessage() + ")";
            } catch (java.net.UnknownHostException e) {
                errorDetail = "IP não encontrado na rede";
            } catch (IOException e) {
                errorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            boolean finalReachable = reachable;
            String finalErrorDetail = errorDetail;
            runOnUiThread(() -> {
                if (finalReachable) {
                    txtConnectionStatus.setText("Ligação estabelecida com sucesso");
                    txtConnectionStatus.setTextColor(0xFF2E7D32);
                } else {
                    txtConnectionStatus.setText("Falha: " + finalErrorDetail);
                    txtConnectionStatus.setTextColor(0xFFD32F2F);
                }
            });
        }).start();
    }

    // Mostra o IP local do telemóvel, para confirmares que está na mesma rede (192.168.1.x) da impressora
    private String getDeviceIpAddress() {
        try {
            android.net.wifi.WifiManager wifiManager =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(android.content.Context.WIFI_SERVICE);
            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            if (ipInt == 0) return "Wi-Fi desligado ou sem ligação";
            return String.format(Locale.US, "%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
        } catch (Exception e) {
            return "Desconhecido";
        }
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }
}