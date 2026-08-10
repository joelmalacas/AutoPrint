package com.example.autoprint;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private ImageButton btnCapture;
    private ImageButton btnFlash;
    private FloatingActionButton btnSwitchCamera;
    private ImageView imgThumbnail;
    private TextView txtStatus;
    private android.view.View captureFlashOverlay;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;

    // Número máximo de fotografias a manter guardadas; ao ultrapassar, apaga-se a mais antiga
    private static final int MAX_PHOTOS = 10;
    private static final String PHOTOS_RELATIVE_PATH = "Pictures/AutoPrint/";

    // Pede a permissão da câmara em tempo de execução
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this,
                            "É necessária a permissão da câmara para usar a app",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        btnCapture = findViewById(R.id.btnCapture);
        btnFlash = findViewById(R.id.btnFlash);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        imgThumbnail = findViewById(R.id.imgThumbnail);
        txtStatus = findViewById(R.id.txtStatus);
        captureFlashOverlay = findViewById(R.id.captureFlashOverlay);

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        btnCapture.setOnClickListener(v -> takePhoto());
        btnFlash.setOnClickListener(v -> toggleFlash());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
                startActivity(new android.content.Intent(MainActivity.this, DefActivity.class)));
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ================= CÂMARA =================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                txtStatus.setText("Erro ao iniciar a câmara");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setFlashMode(flashMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture);
            txtStatus.setText("A câmara está pronta");
        } catch (Exception exc) {
            txtStatus.setText("Erro ao iniciar a câmara");
        }
    }

    // ================= CAPTURA DE FOTO =================

    private void takePhoto() {
        if (imageCapture == null) return;

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoPrint");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        ).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        playCaptureFlashAnimation();
                        Uri savedUri = output.getSavedUri();
                        if (savedUri != null) {
                            imgThumbnail.setImageURI(savedUri);
                        }
                        Toast.makeText(MainActivity.this,
                                "Fotografia guardada", Toast.LENGTH_SHORT).show();

                        // Depois de guardar, garante que não ficam mais de MAX_PHOTOS fotos guardadas
                        enforcePhotoLimit();

                        // Envia a foto para impressão automaticamente
                        if (savedUri != null) {
                            printPhoto(savedUri);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this,
                                "Falha ao guardar a fotografia: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    // ================= IMPRESSÃO AUTOMÁTICA =================

    // Lê o IP/portas guardados nas Definições e envia a foto para impressão.
    // Corre em segundo plano porque carrega a imagem e abre uma ligação de rede.
    private void printPhoto(Uri photoUri) {
        SharedPreferences prefs = getSharedPreferences(DefActivity.PREFS_NAME, MODE_PRIVATE);
        String ip = prefs.getString(DefActivity.KEY_PRINTER_IP, "");
        int port = prefs.getInt(DefActivity.KEY_PRINTER_PORT, 9100);

        if (TextUtils.isEmpty(ip)) {
            txtStatus.setText("Impressora não configurada");
            return;
        }

        txtStatus.setText("A imprimir...");

        new Thread(() -> {
            Bitmap bitmap;
            try {
                bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), photoUri);
            } catch (IOException e) {
                runOnUiThread(() -> txtStatus.setText("Erro ao carregar a foto para impressão"));
                return;
            }

            PrinterHelper.printImage(ip, port, bitmap, new PrinterHelper.PrintCallback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        txtStatus.setText("Impressão enviada");
                        Toast.makeText(MainActivity.this, "Foto enviada para a impressora", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        txtStatus.setText("Falha ao imprimir");
                        Toast.makeText(MainActivity.this, "Erro ao imprimir: " + message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        }).start();
    }

    // ================= LIMITE DE FOTOGRAFIAS GUARDADAS =================

    // Verifica quantas fotos existem na pasta Pictures/AutoPrint e apaga as mais
    // antigas até sobrarem no máximo MAX_PHOTOS. Corre numa thread própria porque
    // acede ao MediaStore (consulta + eliminação), o que não pode ser feito na thread principal.
    private void enforcePhotoLimit() {
        new Thread(() -> {
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            String[] projection = { MediaStore.Images.Media._ID };
            String selection = MediaStore.Images.Media.RELATIVE_PATH + "=?";
            String[] selectionArgs = { PHOTOS_RELATIVE_PATH };
            // Ordenado da mais antiga para a mais recente, para sabermos quais apagar primeiro
            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " ASC";

            List<Uri> allPhotos = new ArrayList<>();

            try (Cursor cursor = getContentResolver().query(
                    collection, projection, selection, selectionArgs, sortOrder)) {

                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        allPhotos.add(ContentUris.withAppendedId(collection, id));
                    }
                }
            } catch (Exception e) {
                return; // Se a consulta falhar, não arrisca apagar nada
            }

            int excess = allPhotos.size() - MAX_PHOTOS;
            if (excess <= 0) return; // Ainda não chegou ao limite

            // Apaga as fotos mais antigas (as primeiras da lista, por estarem ordenadas por data ASC)
            for (int i = 0; i < excess; i++) {
                try {
                    getContentResolver().delete(allPhotos.get(i), null, null);
                } catch (SecurityException se) {
                    // Em alguns casos o sistema pode pedir confirmação extra para apagar;
                    // como a app é dona das fotos que ela própria criou, isto normalmente não acontece.
                }
            }
        }).start();
    }

    // Pequeno "flash" branco no ecrã ao tirar a foto, para dar feedback visual
    private void playCaptureFlashAnimation() {
        captureFlashOverlay.setVisibility(android.view.View.VISIBLE);
        captureFlashOverlay.setAlpha(0.85f);
        captureFlashOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> captureFlashOverlay.setVisibility(android.view.View.GONE))
                .start();
    }

    // ================= FLASH =================

    private void toggleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_ON;
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
        } else {
            flashMode = ImageCapture.FLASH_MODE_OFF;
        }

        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }

        // Por agora usa sempre o mesmo ícone; troca por ic_flash_on / ic_flash_auto
        // quando criares esses desenhos vetoriais.
        btnFlash.setImageResource(R.drawable.ic_flash_off);
    }

    // ================= TROCAR CÂMARA =================

    private void switchCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        bindCameraUseCases();
    }
}